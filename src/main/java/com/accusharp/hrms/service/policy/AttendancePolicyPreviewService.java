package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.policy.AttendancePolicyPreviewDtos;
import com.accusharp.hrms.dto.policy.AttendancePolicyPreviewDtos.DayChange;
import com.accusharp.hrms.dto.policy.AttendancePolicyPreviewDtos.EmployeePreview;
import com.accusharp.hrms.dto.policy.AttendancePolicyPreviewDtos.PreviewRequest;
import com.accusharp.hrms.dto.policy.AttendancePolicyPreviewDtos.PreviewResponse;
import com.accusharp.hrms.dto.policy.AttendancePolicyRuleRequest;
import com.accusharp.hrms.entity.AttendancePolicyOutcome;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.calculation.LopCalculationService;
import com.accusharp.hrms.service.leave.LeaveCalculationService;
import com.accusharp.hrms.service.shift.ShiftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Re-evaluates a stored month under a proposed rule set and reports the diff.
 * Writes nothing.
 *
 * <p>{@code Attendance.md} documents that {@code overtime_window_minutes = 0}
 * silently destroys a month, and that both of the failures in its worked
 * example "were configuration, not code, and both were invisible until someone
 * read the numbers". This feature multiplies that class of risk: HR can now
 * write a rule that docks a whole category half a day each. So the answer is
 * the same one that section reaches - <em>generate a month and look at it
 * before paying it</em> - except that here you can look before saving rather
 * than after.
 *
 * <p>Deliberately evaluates against the <b>stored</b> days rather than
 * regenerating from punches. The question is what this rule set would do to the
 * month HR is looking at, including its manual corrections, not what a fresh
 * generation would produce.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendancePolicyPreviewService {

    /** Above this company-wide LOP delta, the preview says so in as many words. */
    private static final BigDecimal ALARMING_LOP_DELTA = new BigDecimal("5.0");

    private final DailyAttendanceRepository dailyAttendanceRepository;
    private final MonthlyAttendanceSummaryRepository summaryRepository;
    private final AttendancePolicyResolver resolver;
    private final DayPolicyEvaluator dayPolicyEvaluator;
    private final MonthPolicyEvaluator monthPolicyEvaluator;
    private final AttendancePolicyParamsCodec codec;
    private final LopCalculationService lopCalculationService;
    private final LeaveCalculationService leaveCalculationService;
    private final EmployeeService employeeService;
    private final ShiftService shiftService;

    @Transactional(readOnly = true)
    public PreviewResponse preview(PreviewRequest request) {
        YearMonth month = request.getMonth();
        List<Employee> employees = resolveEmployees(request.getUserIds());
        List<AttendancePolicyRule> proposed = toTransientRules(request.getRules());

        List<EmployeePreview> previews = new ArrayList<>();
        BigDecimal lopDelta = BigDecimal.ZERO;
        BigDecimal overtimeDelta = BigDecimal.ZERO;

        for (Employee employee : employees) {
            EmployeePreview preview = previewOne(employee, month, proposed);
            if (preview == null) {
                continue;
            }
            previews.add(preview);
            lopDelta = lopDelta.add(preview.lopDelta());
            overtimeDelta = overtimeDelta.add(preview.overtimeAfter().subtract(preview.overtimeBefore()));
        }

        List<EmployeePreview> affected = previews.stream()
                .filter(p -> p.lopDelta().signum() != 0 || !p.dayChanges().isEmpty())
                .sorted(Comparator.comparing(EmployeePreview::lopDelta).reversed())
                .toList();

        return new PreviewResponse(month, previews.size(), affected.size(),
                lopDelta.setScale(1, RoundingMode.HALF_UP),
                overtimeDelta.setScale(2, RoundingMode.HALF_UP),
                warnings(request.getRules(), lopDelta, previews.size(), affected.size()),
                affected);
    }

    private EmployeePreview previewOne(Employee employee, YearMonth month,
                                       List<AttendancePolicyRule> proposed) {
        String userId = employee.getUserId();
        List<DailyAttendance> stored = dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        userId, month.atDay(1), month.atEndOfMonth());
        if (stored.isEmpty()) {
            // Nothing generated for this employee - nothing to re-evaluate. Not
            // an error: a preview across a whole company will always include
            // people who joined later or were never rostered.
            return null;
        }

        Map<String, Shift> shifts = shiftCache(employee, stored);

        Set<LocalDate> workingDates = stored.stream()
                .filter(DailyAttendance::isWorkingDay)
                .map(DailyAttendance::getAttendanceDate)
                .collect(Collectors.toSet());

        List<DailyAttendanceResponse> after = new ArrayList<>(stored.size());
        List<DayChange> changes = new ArrayList<>();
        Set<LocalDate> latePenalised = new HashSet<>();
        BigDecimal overtimeBefore = BigDecimal.ZERO;
        BigDecimal overtimeAfter = BigDecimal.ZERO;

        for (DailyAttendance day : stored) {
            overtimeBefore = overtimeBefore.add(day.getOvertimeHours());
            Shift shift = shifts.get(day.getShiftCode());
            ResolvedPolicy policy = resolveTransient(proposed, employee, day.getAttendanceDate());

            if (shift == null || policy.isEmpty()) {
                after.add(toResponse(day, day.getStatus(), day.getLateMinutes(), day.getOvertimeHours()));
                overtimeAfter = overtimeAfter.add(day.getOvertimeHours());
                continue;
            }

            long shiftMinutes = (long) shift.getWorkingHours() * 60;
            long workedMinutes = day.getWorkingHours()
                    .multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).longValue();
            long overtimeMinutes = day.getOvertimeHours()
                    .multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).longValue();

            DayPolicyEvaluator.DayPolicyResult applied = dayPolicyEvaluator.apply(
                    new DayPolicyEvaluator.DayContext(userId, day.getAttendanceDate(), shift,
                            day.getFirstIn() == null ? 0 : (day.getLastOut() == null ? 1 : 2),
                            day.getFirstIn(), day.getLastOut(), workedMinutes, shiftMinutes,
                            day.getLateMinutes(), day.getEarlyExitMinutes(), overtimeMinutes,
                            day.isWeekOff(), day.isHoliday(), day.getStatus()),
                    policy);

            BigDecimal newOvertime = BigDecimal.valueOf(applied.overtimeMinutes())
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
            overtimeAfter = overtimeAfter.add(newOvertime);

            applied.trace().stream()
                    .filter(row -> row.getRuleType() == RuleType.LATE_ARRIVAL)
                    .forEach(row -> latePenalised.add(row.getAttendanceDate()));

            if (applied.status() != day.getStatus()) {
                changes.add(new DayChange(day.getAttendanceDate(), day.getStatus(), applied.status(),
                        applied.trace().stream()
                                .map(row -> row.getExplanation())
                                .collect(Collectors.joining("; "))));
            }
            after.add(toResponse(day, applied.status(), applied.lateMinutes(), newOvertime));
        }

        MonthPolicyEvaluator.MonthPolicyResult monthResult = monthPolicyEvaluator.apply(
                userId, month, after, workingDates, latePenalised,
                resolveTransient(proposed, employee, month.atDay(1)));

        BigDecimal lopBefore = summaryRepository.findByUserIdAndMonth(userId, month.toString())
                .map(summary -> summary.getLopDays())
                .orElse(BigDecimal.ZERO);

        BigDecimal presentAfter = after.stream()
                .filter(d -> workingDates.contains(d.attendanceDate()))
                .map(d -> d.status().dayFraction())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(1, RoundingMode.HALF_UP);

        BigDecimal paidLeave = leaveCalculationService.paidLeaveDays(
                leaveCalculationService.approvedLeaveDaysInMonth(userId, month), workingDates);

        BigDecimal lopAfter = lopCalculationService
                .calculateLopDays(BigDecimal.valueOf(workingDates.size()), presentAfter, paidLeave)
                .add(monthResult.lopDays())
                .min(BigDecimal.valueOf(workingDates.size()))
                .setScale(1, RoundingMode.HALF_UP);

        return new EmployeePreview(userId, employee.getEmployeeName(),
                lopBefore, lopAfter, lopAfter.subtract(lopBefore).setScale(1, RoundingMode.HALF_UP),
                overtimeBefore.setScale(2, RoundingMode.HALF_UP),
                overtimeAfter.setScale(2, RoundingMode.HALF_UP),
                changes,
                monthResult.outcomes().stream().map(AttendancePolicyOutcome::getExplanation).toList());
    }

    /**
     * Warnings a company should read before saving, in the spirit of
     * {@code GET /api/shifts}'s derived {@code warnings} - configuration that is
     * legal but will produce attendance nobody wants.
     */
    private List<String> warnings(List<AttendancePolicyRuleRequest> rules, BigDecimal lopDelta,
                                  int evaluated, int affected) {
        List<String> warnings = new ArrayList<>();

        if (lopDelta.compareTo(ALARMING_LOP_DELTA) > 0) {
            warnings.add("this rule set adds " + lopDelta.setScale(1, RoundingMode.HALF_UP)
                    + " LOP days across " + affected + " of " + evaluated
                    + " employees - check that is intended before saving it");
        }

        boolean hasLateArrival = rules.stream().anyMatch(r -> r.getRuleType() == RuleType.LATE_ARRIVAL);
        boolean hasAccumulation = rules.stream()
                .anyMatch(r -> r.getRuleType() == RuleType.LATE_MARK_ACCUMULATION);
        if (hasLateArrival && hasAccumulation) {
            warnings.add("LATE_ARRIVAL and LATE_MARK_ACCUMULATION are both configured;"
                    + " days already docked for lateness are excluded from the late-mark count,"
                    + " so nobody is charged twice - but check this is the combination you meant");
        }

        rules.stream()
                .filter(r -> r.getRuleType() == RuleType.LATE_ARRIVAL && r.isEnabled())
                .filter(r -> r.getParams().toString().contains("\"ABSENT\""))
                .findFirst()
                .ifPresent(r -> warnings.add("a LATE_ARRIVAL rule with penaltyStatus ABSENT turns a"
                        + " late arrival into a full unpaid day on a day that was worked in full,"
                        + " and produces a month that reads exactly like genuine absence -"
                        + " this is the single most damaging misconfiguration this engine allows"));

        if (affected == 0) {
            warnings.add("this rule set changes nothing for this month -"
                    + " check the scope reference matches employees who actually exist");
        }
        return warnings;
    }

    /**
     * Builds rule objects that are never saved. They carry a null id, so any
     * trace row derived from them is equally transient - which is safe because
     * the preview persists none of it.
     */
    private List<AttendancePolicyRule> toTransientRules(List<AttendancePolicyRuleRequest> requests) {
        List<AttendancePolicyRule> rules = new ArrayList<>(requests.size());
        for (AttendancePolicyRuleRequest request : requests) {
            String scopeRef = request.getScope().requiresRef()
                    ? request.getScopeRef() : RuleScope.ANY;
            if (scopeRef == null || scopeRef.isBlank()) {
                throw new BusinessRuleException("scopeRef is required for scope " + request.getScope());
            }
            // Validated exactly as a real save would validate it - a preview of
            // a rule set that could not be saved is not worth showing.
            codec.parse(request.getRuleType(), request.getParams().toString());

            rules.add(AttendancePolicyRule.builder()
                    .scope(request.getScope()).scopeRef(scopeRef).ruleType(request.getRuleType())
                    .version(0).effectiveFrom(request.getEffectiveFrom())
                    .enabled(request.isEnabled()).params(request.getParams().toString())
                    .build());
        }
        return rules;
    }

    /**
     * Resolves against the proposed rules instead of the stored ones.
     * {@code resolve} is a pure function of the list it is handed - it never
     * touches the repository - so the same bean answers for a hypothetical rule
     * set as for the real one, and the preview cannot drift from what a real
     * generation would decide.
     */
    private ResolvedPolicy resolveTransient(List<AttendancePolicyRule> proposed,
                                            Employee employee, LocalDate date) {
        return resolver.resolve(proposed, employee, date);
    }

    private Map<String, Shift> shiftCache(Employee employee, List<DailyAttendance> stored) {
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        return stored.stream()
                .map(DailyAttendance::getShiftCode)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(code -> {
                    try {
                        return shiftService.getByCode(code, companyId);
                    } catch (RuntimeException ex) {
                        // A shift renamed or removed since the month was generated.
                        // The day is reported unchanged rather than failing the
                        // whole preview for everybody else.
                        log.warn("attendance.policy.preview unknown shift code={} company={}", code, companyId);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(Shift::getShiftCode, shift -> shift, (a, b) -> a));
    }

    private DailyAttendanceResponse toResponse(DailyAttendance day, AttendanceStatus status,
                                               int lateMinutes, BigDecimal overtimeHours) {
        return new DailyAttendanceResponse(day.getUserId(), day.getAttendanceDate(), day.getShiftCode(),
                day.getFirstIn(), day.getLastOut(), day.getWorkingHours(), day.getBreakHours(),
                overtimeHours, lateMinutes, day.getEarlyExitMinutes(), day.isInvalidPunch(), status);
    }

    private List<Employee> resolveEmployees(List<String> userIds) {
        return userIds == null || userIds.isEmpty()
                ? employeeService.getActiveEntities()
                : userIds.stream().map(employeeService::getEntityByUserId).toList();
    }
}
