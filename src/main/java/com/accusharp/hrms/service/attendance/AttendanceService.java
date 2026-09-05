package com.accusharp.hrms.service.attendance;

import com.accusharp.hrms.dto.AttendanceCorrectionRequest;
import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.entity.AttendancePolicyApplication;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.AttendanceRule;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.AttendancePolicyApplicationRepository;
import com.accusharp.hrms.repository.AttendancePolicyOutcomeRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.AttendanceRuleService;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.HolidayService;
import com.accusharp.hrms.service.calculation.AttendanceCalculationService;
import com.accusharp.hrms.service.calculation.AttendanceWindowResolver;
import com.accusharp.hrms.service.calculation.LopCalculationService;
import com.accusharp.hrms.service.policy.AttendancePolicyResolver;
import com.accusharp.hrms.service.policy.MonthPolicyEvaluator;
import com.accusharp.hrms.service.policy.ResolvedPolicy;
import com.accusharp.hrms.service.leave.LeaveCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Attendance is an explicit, stored artifact - not a view that re-derives
 * itself on every read.
 *
 * <p>The lifecycle is generate, review, correct, pay:
 * <pre>
 *   generate(month)  -&gt; one DailyAttendance row per rostered day, GENERATED
 *   correct(day)     -&gt; row becomes MANUAL and survives the next generation
 *   payroll          -&gt; reads the stored rows and locks them
 * </pre>
 *
 * <p>Two rules make the corrections stick, and both are load-bearing: a
 * regeneration preserves {@code MANUAL} rows unless told otherwise, and a
 * <em>read</em> never writes. Before this existed, both a plain monthly GET and
 * payroll itself recomputed from raw punches, so any correction an admin made
 * was destroyed by the next request that happened to touch the month.
 *
 * <p>Monthly summaries remain a cache, but of the stored days rather than of
 * raw punches - so LOP and payroll inherit corrections automatically.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private static final int DAY_SCALE = 1;
    private static final int HOUR_SCALE = 2;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final DeviceLogRepository deviceLogRepository;
    private final DailyAttendanceRepository dailyAttendanceRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;
    private final MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    private final AttendanceCalculationService attendanceCalculationService;
    private final AttendanceWindowResolver windowResolver;
    private final LeaveCalculationService leaveCalculationService;
    private final LopCalculationService lopCalculationService;
    private final HolidayService holidayService;
    private final EmployeeService employeeService;
    private final AuditService auditService;
    private final AttendanceRuleService attendanceRuleService;
    private final AttendancePolicyResolver attendancePolicyResolver;
    private final MonthPolicyEvaluator monthPolicyEvaluator;
    private final AttendancePolicyApplicationRepository policyApplicationRepository;
    private final AttendancePolicyOutcomeRepository policyOutcomeRepository;

    // ---- generation --------------------------------------------------------

    /**
     * Builds the stored attendance for the period from raw punches and the
     * roster. Safe to rerun: locked days are left alone and manual corrections
     * are preserved unless {@code overwriteManual} is set.
     */
    @Transactional
    public AttendanceGenerationResponse generate(AttendanceGenerationRequest request) {
        assertHrOrAdmin(request.getGeneratedBy());

        YearMonth month = request.getMonth();
        List<Employee> employees = resolveEmployees(request.getUserIds());
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        int generated = 0;
        int unrostered = 0;
        int manualPreserved = 0;
        int lockedSkipped = 0;
        List<String> withoutRoster = new ArrayList<>();
        List<AttendanceGenerationResponse.DayChange> changes = new ArrayList<>();

        // The rule and holiday calendar only vary by company, not by employee -
        // resolved once per distinct company in this batch (in practice exactly
        // one, the caller's own) instead of once per employee.
        Map<Long, CompanyGenerationContext> contextsByCompany = new HashMap<>();

        for (Employee employee : employees) {
            Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            CompanyGenerationContext context = contextsByCompany.computeIfAbsent(companyId,
                    id -> resolveCompanyContext(id, first, last));

            GenerationTally tally = generateFor(employee, month, request.getGeneratedBy(),
                    request.isOverwriteManual(), request.isDryRun(),
                    request.isIncludeUnrostered(), context);

            generated += tally.generated();
            unrostered += tally.unrosteredGenerated();
            manualPreserved += tally.manualPreserved();
            lockedSkipped += tally.lockedSkipped();
            changes.addAll(tally.changes());
            if (tally.rosterDays() == 0) {
                withoutRoster.add(employee.getUserId());
            }
        }

        log.info("attendance.generate month={} employees={} generated={} unrostered={} manualPreserved={} "
                        + "lockedSkipped={} dryRun={}",
                month, employees.size(), generated, unrostered, manualPreserved, lockedSkipped,
                request.isDryRun());
        if (unrostered > 0) {
            log.warn("attendance.generate.unrostered month={} days={} employeesWithNoRosterAtAll={} - these days "
                            + "were written blank and ABSENT because nobody rostered them, and each one is loss "
                            + "of pay. Fix the roster and regenerate rather than correcting them one by one.",
                    month, unrostered, withoutRoster.size());
        }

        return new AttendanceGenerationResponse(month, employees.size(), generated, unrostered,
                manualPreserved, lockedSkipped, withoutRoster, request.isDryRun(), changes);
    }

    private CompanyGenerationContext resolveCompanyContext(Long companyId, LocalDate first, LocalDate last) {
        AttendanceRule rule = attendanceRuleService.getActiveRuleForCompany(companyId);
        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, first, last);
        // Loaded once per company alongside the rule and the holiday calendar,
        // not once per employee per day per rule type - the same batching this
        // method already exists to do. Empty for every company that has
        // configured nothing, which is what makes resolution free for them.
        List<AttendancePolicyRule> policyRules = attendancePolicyResolver.loadCompanyRules(companyId, last);
        return new CompanyGenerationContext(rule, holidays, policyRules);
    }

    private GenerationTally generateFor(Employee employee, YearMonth month, String generatedBy,
                                        boolean overwriteManual, boolean dryRun,
                                        boolean includeUnrostered,
                                        CompanyGenerationContext context) {
        AttendanceRule rule = context.rule();
        Set<LocalDate> holidays = context.holidays();
        String userId = employee.getUserId();
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysBetween(userId, first.minusDays(1), last);

        // Resolved from the day before the period, not the first of it. The last
        // night shift of the previous month reaches into this one, and if that
        // day was generated before this month's roster existed it claimed
        // punches this month's first day now legitimately owns. Recomputing it
        // here is what stops the same punch being counted by two stored days -
        // the failure only appears when a month is generated before the next
        // period is rostered, so nothing about generating in order reveals it.
        LocalDate previousDay = first.minusDays(1);
        ResolvedRange resolved = resolve(userId, previousDay, last, rule);

        Map<LocalDate, DailyAttendance> existing = indexByDate(dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(userId, previousDay, last));

        List<DailyAttendance> toSave = new ArrayList<>();
        List<AttendancePolicyApplication> policyTrace = new ArrayList<>();
        List<LocalDate> rewrittenDates = new ArrayList<>();
        int rosterDays = 0;
        int generated = 0;
        int unrosteredGenerated = 0;
        int manualPreserved = 0;
        int lockedSkipped = 0;
        boolean previousMonthTouched = false;

        for (AttendanceWindowResolver.DayWindow window : resolved.days()) {
            ShiftSchedule schedule = window.schedule();
            LocalDate date = schedule.getShiftDate();
            DailyAttendance current = existing.get(date);
            boolean isNeighbour = date.isBefore(first);

            // The trailing day of the previous month is refreshed, never created:
            // generating June must not quietly bring 31 May into existence, and a
            // day nobody has generated yet has nothing that needs correcting.
            if (isNeighbour && current == null) {
                continue;
            }
            if (!isNeighbour) {
                rosterDays++;
            }

            if (current != null && current.isLocked()) {
                if (!isNeighbour) {
                    lockedSkipped++;
                }
                continue;
            }
            if (current != null && current.getRecordStatus().survivesRegeneration() && !overwriteManual) {
                if (!isNeighbour) {
                    manualPreserved++;
                }
                continue;
            }

            AttendanceCalculationService.PolicyAwareDay computed = computeDay(employee, window,
                    resolved.punchesOn(date), holidays, leaveDays, rule, context.policyRules());
            DailyAttendance record = current != null ? current : new DailyAttendance();
            applyComputed(record, userId, schedule, computed.day(), holidays.contains(date));

            // The trace is rebuilt with the day it explains. Only days this run
            // actually writes get here - a locked or MANUAL day was skipped
            // above, so it keeps whatever trace it already had.
            rewrittenDates.add(date);
            policyTrace.addAll(computed.trace());

            record.setRecordStatus(AttendanceRecordStatus.GENERATED);
            record.setRemarks(null);
            record.setUpdatedBy(null);
            record.setUpdatedAt(null);
            record.setGeneratedAt(Instant.now());
            record.setGeneratedBy(generatedBy);

            toSave.add(record);
            if (isNeighbour) {
                previousMonthTouched = true;
            } else {
                generated++;
            }
        }

        // Days nobody rostered. The roster loop above can only ever produce a
        // day the roster already knows about, so before this an employee HR
        // forgot to schedule generated nothing at all - see
        // AttendanceGenerationRequest#includeUnrostered for why a blank ABSENT
        // day beats no day.
        if (includeUnrostered) {
            Set<LocalDate> rostered = resolved.days().stream()
                    .map(AttendanceWindowResolver.DayWindow::date)
                    .collect(Collectors.toSet());
            List<LocalDate> gaps = unrosteredDates(employee, first, last, rostered);
            Map<LocalDate, List<DeviceLog>> loose = loosePunchesByDate(userId, gaps, resolved);

            for (LocalDate date : gaps) {
                DailyAttendance current = existing.get(date);
                if (current != null && current.isLocked()) {
                    lockedSkipped++;
                    continue;
                }
                if (current != null && current.getRecordStatus().survivesRegeneration() && !overwriteManual) {
                    manualPreserved++;
                    continue;
                }

                DailyAttendance record = current != null ? current : new DailyAttendance();
                applyUnrostered(record, userId, date, loose.getOrDefault(date, List.of()),
                        holidays.contains(date), leaveDays.containsKey(date));

                // No trace to write - the day-scoped policy engine reads the
                // shift (grace, working hours, overtime window) that this day by
                // definition has none of - but any trace a previous run left
                // behind, from when the day still had a roster row, must go.
                rewrittenDates.add(date);

                record.setRecordStatus(AttendanceRecordStatus.GENERATED);
                record.setRemarks(null);
                record.setUpdatedBy(null);
                record.setUpdatedAt(null);
                record.setGeneratedAt(Instant.now());
                record.setGeneratedBy(generatedBy);

                toSave.add(record);
                generated++;
                unrosteredGenerated++;
            }
        }

        if (!dryRun) {
            dailyAttendanceRepository.saveAll(toSave);
            // Delete-then-insert rather than merge: the trace is derived, so a
            // rerun must replace it wholesale rather than accumulate a second
            // row per rule per run. Scoped to the dates this run rewrote.
            if (!rewrittenDates.isEmpty()) {
                policyApplicationRepository.deleteByUserIdAndAttendanceDateIn(userId, rewrittenDates);
            }
            if (!policyTrace.isEmpty()) {
                policyApplicationRepository.saveAll(policyTrace);
            }
            rebuildSummary(employee, month);
            if (previousMonthTouched) {
                rebuildSummary(employee, month.minusMonths(1));
            }
        }

        return new GenerationTally(rosterDays, generated, unrosteredGenerated, manualPreserved,
                lockedSkipped, describeChanges(toSave, existing, dryRun));
    }

    /**
     * The days of the period this employee was employed for but nobody
     * rostered.
     *
     * <p>Bounded by joining and relieving on purpose: nobody is absent before
     * they were hired or after they left, and payroll already caps payable days
     * by the same window - filling outside it would manufacture loss of pay for
     * days the employee was not on the books, which is the one error this whole
     * feature must not make.
     */
    private List<LocalDate> unrosteredDates(Employee employee, LocalDate first, LocalDate last,
                                            Set<LocalDate> rostered) {
        LocalDate joined = employee.getJoiningDate();
        LocalDate relieved = employee.getRelievingDate();

        List<LocalDate> gaps = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (rostered.contains(date)) {
                continue;
            }
            if (joined != null && date.isBefore(joined)) {
                continue;
            }
            if (relieved != null && date.isAfter(relieved)) {
                continue;
            }
            gaps.add(date);
        }
        return gaps;
    }

    /**
     * Punches on unrostered days, keyed by their own calendar date.
     *
     * <p>Recorded rather than dropped. Someone who badged in on a day HR forgot
     * to schedule still gets {@code ABSENT} - with no shift there is nothing to
     * measure the day against, so no threshold can call it present - but the
     * times are the evidence that says the roster is what is wrong, and a day
     * that decides pay is the last place to throw evidence away.
     *
     * <p>A punch a rostered day already claimed is excluded: a night shift's
     * exit falls on the next calendar date, and if that date happens to be
     * unrostered the punch belongs to the shift that earned it, not to the gap
     * it landed in. Ownership is compared by punch time, which
     * {@link AttendanceWindowResolver#dedupe} has already made unique.
     *
     * <p>Calendar date, not a window, is the rule here for the same reason the
     * status is blank: without a shift there is no window to speak of.
     */
    private Map<LocalDate, List<DeviceLog>> loosePunchesByDate(String userId, List<LocalDate> gaps,
                                                              ResolvedRange resolved) {
        if (gaps.isEmpty()) {
            return Map.of();
        }
        // One query, over the gap dates only - and none at all for the common
        // case of an unrostered employee who never punched, where it comes back
        // empty. The resolved range's own fetch cannot be reused: it spans the
        // rostered windows, so a gap before the first or after the last of them
        // was never read.
        Set<LocalDateTime> alreadyOwned = resolved.resolution().punchesByDate().values().stream()
                .flatMap(List::stream)
                .map(DeviceLog::getLogDate)
                .collect(Collectors.toSet());

        List<DeviceLog> punches = windowResolver.dedupe(deviceLogRepository
                .findAllByUserIdAndLogDateGreaterThanEqualAndLogDateLessThanOrderByLogDateAsc(
                        userId, gaps.getFirst().atStartOfDay(), gaps.getLast().plusDays(1).atStartOfDay()));

        Set<LocalDate> gapDates = new HashSet<>(gaps);
        Map<LocalDate, List<DeviceLog>> byDate = new HashMap<>();
        for (DeviceLog punch : punches) {
            LocalDate date = punch.getLogDate().toLocalDate();
            if (!gapDates.contains(date) || alreadyOwned.contains(punch.getLogDate())) {
                continue;
            }
            byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(punch);
        }
        return byDate;
    }

    /**
     * A day with no shift behind it: blank, and worth nothing until someone
     * either rosters it or corrects it.
     *
     * <p>Hours stay at zero even when punches exist, because every figure the
     * engine derives - worked minutes net of the break, lateness against grace,
     * overtime past the shift length - is measured against a shift. Inventing
     * those from a missing roster would be a guess, and it would be a guess
     * that changes pay.
     *
     * <p>{@code weekOff} is false: nothing said this day was off. That makes it
     * a working day and therefore loss of pay, which is the whole point - the
     * gap is visible in the figure HR reviews rather than silently absent from
     * it. A mandatory holiday still reads as a holiday, and approved leave
     * still reads as leave, so neither can be turned into LOP by a missing
     * roster row.
     */
    private void applyUnrostered(DailyAttendance record, String userId, LocalDate date,
                                 List<DeviceLog> punches, boolean holiday, boolean onLeave) {
        record.setUserId(userId);
        record.setAttendanceDate(date);
        record.setShiftCode(null);
        record.setFirstIn(punches.isEmpty() ? null : punches.getFirst().getLogDate());
        record.setLastOut(punches.size() < 2 ? null : punches.getLast().getLogDate());
        record.setWorkingHours(zeroHours());
        record.setBreakHours(zeroHours());
        record.setOvertimeHours(zeroHours());
        record.setLateMinutes(0);
        record.setEarlyExitMinutes(0);
        // Not an invalid punch: the device worked, the roster is what is
        // missing. Flagging it here would send HR to fix a reader that is fine.
        record.setInvalidPunch(false);
        record.setWeekOff(false);
        record.setHoliday(holiday);
        record.setStatus(unrosteredStatus(holiday, onLeave));
    }

    /**
     * Mirrors {@code AttendanceCalculationService.resolveNonWorkingStatus} minus
     * the two cases a day with no shift cannot be in: there is no weekly off
     * without a roster row to declare one, and a lone punch is not
     * {@code INVALID_PUNCH} here because no window was violated.
     */
    private AttendanceStatus unrosteredStatus(boolean holiday, boolean onLeave) {
        if (onLeave) {
            return AttendanceStatus.ON_LEAVE;
        }
        if (holiday) {
            return AttendanceStatus.HOLIDAY;
        }
        return AttendanceStatus.ABSENT;
    }

    /**
     * What a run changed, per day, for the dry-run report. Built only when a
     * dry run asked for it - a real run reports counts, and the stored rows
     * themselves are the record.
     */
    private List<AttendanceGenerationResponse.DayChange> describeChanges(
            List<DailyAttendance> toSave, Map<LocalDate, DailyAttendance> existingBefore, boolean dryRun) {
        if (!dryRun) {
            return List.of();
        }
        List<AttendanceGenerationResponse.DayChange> changes = new ArrayList<>();
        for (DailyAttendance record : toSave) {
            DailyAttendance before = existingBefore.get(record.getAttendanceDate());
            AttendanceStatus previousStatus = before == null ? null : before.getStatus();
            if (previousStatus == record.getStatus()
                    && before != null
                    && Objects.equals(before.getFirstIn(), record.getFirstIn())
                    && Objects.equals(before.getLastOut(), record.getLastOut())) {
                continue;
            }
            changes.add(new AttendanceGenerationResponse.DayChange(
                    record.getUserId(), record.getAttendanceDate(), record.getShiftCode(),
                    previousStatus, record.getStatus(),
                    before == null ? null : before.getFirstIn(), record.getFirstIn(),
                    before == null ? null : before.getLastOut(), record.getLastOut()));
        }
        return changes;
    }

    // ---- correction --------------------------------------------------------

    /**
     * Corrects one generated day. Supplied punch times are run back through
     * {@link AttendanceCalculationService} - the identical path a device punch
     * takes - so a hand-fixed day can never obey different rules from a
     * machine-read one.
     */
    @Transactional
    public AttendanceRecordResponse correctDay(String userId, LocalDate date,
                                               AttendanceCorrectionRequest request) {
        assertHrOrAdmin(request.getUpdatedBy());
        // Tenant check on the record being WRITTEN to, not just the caller's own identity above -
        // found during a full security audit: this method previously never resolved the target
        // userId at all, so an HR/ADMIN at any company could correct another company's attendance
        // by userId, having only proven they hold HR/ADMIN *somewhere*.
        Employee employee = employeeService.getEntityByUserId(userId);
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();

        DailyAttendance record = dailyAttendanceRepository.findByUserIdAndAttendanceDate(userId, date)
                .orElseThrow(() -> NotFoundException.of("Attendance",
                        userId + " on " + date + " - generate the month first"));

        if (record.isLocked()) {
            throw new BusinessRuleException("Attendance for " + userId + " on " + date
                    + " is locked because payroll has been generated - unlock the month first");
        }

        boolean hasPunches = request.getFirstIn() != null && request.getLastOut() != null;
        if (!hasPunches && request.getStatus() == null) {
            throw new BusinessRuleException(
                    "Provide both firstIn and lastOut, or a status to force");
        }
        if (hasPunches && !request.getLastOut().isAfter(request.getFirstIn())) {
            throw new BusinessRuleException("lastOut must be after firstIn");
        }

        // Optional, not required. Generation now writes a blank ABSENT day for a
        // date nobody rostered, and a day HR can see but cannot correct would be
        // worse than the blank month that day exists to replace. What the missing
        // shift does cost is the recompute below: with no start time, grace,
        // break or shift length there is nothing to derive hours from, so such a
        // day can only be corrected by forcing a status.
        ShiftSchedule schedule = shiftScheduleRepository.findByUserIdAndShiftDate(userId, date).orElse(null);
        if (schedule == null && request.getStatus() == null) {
            throw new BusinessRuleException("No shift is rostered for " + userId + " on " + date
                    + " - assign the shift and regenerate, or supply an explicit status");
        }

        if (hasPunches && schedule != null) {
            Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, date, date);
            Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                    leaveCalculationService.approvedLeaveDaysBetween(userId, date, date);
            AttendanceRule rule = attendanceRuleService.getActiveRuleForCompany(companyId);

            List<DeviceLog> corrected = List.of(
                    DeviceLog.builder().userId(userId).logDate(request.getFirstIn()).build(),
                    DeviceLog.builder().userId(userId).logDate(request.getLastOut()).build());

            // The same day-scoped policy a device-read day gets. Attendance.md's
            // promise is that a hand-fixed day "derives its hours, lateness and
            // overtime by identical rules - there is no second code path that
            // can drift", and exempting corrections from policy would create
            // exactly that second path: the same punches would score differently
            // depending on whether a device or a human supplied them. An
            // explicit `status` in the request still overrides whatever policy
            // concluded, immediately below - HR remains the final word.
            ResolvedPolicy policy = attendancePolicyResolver.resolve(
                    attendancePolicyResolver.loadCompanyRules(companyId, date), employee, date);

            AttendanceCalculationService.PolicyAwareDay computed =
                    attendanceCalculationService.calculateDay(
                            userId, date, schedule.getShift(), corrected, schedule.isWeekOff(),
                            holidays.contains(date), leaveDays.containsKey(date), rule, policy);

            applyComputed(record, userId, schedule, computed.day(), holidays.contains(date));

            // The correction's own trace replaces the generated one for this day.
            policyApplicationRepository.deleteByUserIdAndAttendanceDateIn(userId, List.of(date));
            if (!computed.trace().isEmpty()) {
                policyApplicationRepository.saveAll(computed.trace());
            }

            // An explicit status still wins - the admin may know the day was
            // half a day even though the corrected times say otherwise.
            if (request.getStatus() != null) {
                record.setStatus(request.getStatus());
            }
        } else {
            applyForcedStatus(record, schedule, request.getStatus());
            // Supplied punches on an unrostered day are kept as the record of
            // what the device saw, even though no hours could be derived from
            // them - the same reason generation stores them rather than
            // dropping them.
            if (hasPunches) {
                record.setFirstIn(request.getFirstIn());
                record.setLastOut(request.getLastOut());
            }
        }

        record.setRecordStatus(AttendanceRecordStatus.MANUAL);
        record.setInvalidPunch(false);
        record.setRemarks(request.getRemarks());
        record.setUpdatedBy(request.getUpdatedBy());
        record.setUpdatedAt(Instant.now());

        DailyAttendance saved = dailyAttendanceRepository.save(record);
        rebuildSummary(employee, YearMonth.from(date));

        log.info("attendance.correct userId={} date={} status={} by={}",
                userId, date, saved.getStatus(), request.getUpdatedBy());
        auditService.record("ATTENDANCE_CORRECT", "DailyAttendance", userId + " " + date,
                AuditOutcome.SUCCESS, "status=" + saved.getStatus() + " remarks=" + request.getRemarks());
        return AttendanceRecordResponse.of(saved);
    }

    /**
     * A day with no punches at all to correct. Hours follow the shift, because
     * declaring someone present means declaring they worked the shift.
     *
     * @param schedule the rostered shift, or null for a day nobody rostered -
     *                 where the hours stay zero because there is no shift to
     *                 say how long a full day is. The status still carries the
     *                 day's value for payroll, which pays from
     *                 {@code presentDays} rather than from these hours.
     */
    private void applyForcedStatus(DailyAttendance record, ShiftSchedule schedule, AttendanceStatus status) {
        BigDecimal shiftHours = schedule == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(schedule.getShift().getWorkingHours());

        BigDecimal workingHours = switch (status) {
            case PRESENT -> shiftHours;
            case HALF_DAY -> shiftHours.multiply(HALF);
            default -> BigDecimal.ZERO;
        };

        record.setStatus(status);
        record.setFirstIn(null);
        record.setLastOut(null);
        record.setWorkingHours(workingHours.setScale(HOUR_SCALE, RoundingMode.HALF_UP));
        record.setBreakHours(zeroHours());
        record.setOvertimeHours(zeroHours());
        record.setLateMinutes(0);
        record.setEarlyExitMinutes(0);
    }

    // ---- locking -----------------------------------------------------------

    /** Freezes the month so an already-paid period stays reproducible. */
    @Transactional
    public int lockMonth(String userId, YearMonth month) {
        Employee employee = employeeService.getEntityByUserId(userId); // tenant check; see unlockMonth's Javadoc
        return lockMonth(employee, month);
    }

    /**
     * Same as {@link #lockMonth(String, YearMonth)}, for a caller (e.g.
     * {@code PayrollService.build}) that already resolved and tenant-checked
     * the target {@link Employee} in the same transaction - skips the
     * otherwise-redundant repeat lookup.
     */
    @Transactional
    public int lockMonth(Employee employee, YearMonth month) {
        return setLocked(employee.getUserId(), month, true);
    }

    /**
     * Reopens a paid month for correction. The payroll already generated is not
     * touched - regenerate it afterwards to pick the corrections up.
     *
     * <p>Resolves {@code userId} through the tenant-checked
     * {@code EmployeeService} before touching anything - found missing during
     * a full security audit, alongside the identical gap in
     * {@link #correctDay}. {@code assertHrOrAdmin(actorId)} only proves the
     * <em>caller</em> holds HR/ADMIN somewhere; it says nothing about which
     * company the records being locked/unlocked belong to.
     */
    @Transactional
    public int unlockMonth(String userId, YearMonth month, String actorId) {
        assertHrOrAdmin(actorId);
        employeeService.getEntityByUserId(userId);
        log.info("attendance.unlock userId={} month={} by={}", userId, month, actorId);
        int updated = setLocked(userId, month, false);
        auditService.record("ATTENDANCE_UNLOCK", "DailyAttendance", userId + " " + month,
                AuditOutcome.SUCCESS, "daysUnlocked=" + updated);
        return updated;
    }

    private int setLocked(String userId, YearMonth month, boolean locked) {
        List<DailyAttendance> records = dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        userId, month.atDay(1), month.atEndOfMonth()).stream()
                .filter(record -> record.isLocked() != locked)
                .peek(record -> record.setLocked(locked))
                .toList();
        dailyAttendanceRepository.saveAll(records);
        return records.size();
    }

    // ---- reads -------------------------------------------------------------

    /**
     * Day-by-day attendance for an arbitrary window: the stored record where
     * one exists, a transient preview computed from punches where it does not.
     * Nothing is written either way.
     */
    @Transactional(readOnly = true)
    public List<DailyAttendanceResponse> getDailyAttendance(String userId, LocalDate fromDate, LocalDate toDate) {
        Employee employee = employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        if (fromDate.isAfter(toDate)) {
            throw new BusinessRuleException("fromDate must be on or before toDate");
        }

        Map<LocalDate, DailyAttendance> stored = indexByDate(dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(userId, fromDate, toDate));

        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        AttendanceRule rule = attendanceRuleService.getActiveRuleForCompany(companyId);
        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, fromDate, toDate);
        // Read across the whole window, not just the first month of it.
        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysBetween(userId, fromDate, toDate);

        List<AttendancePolicyRule> policyRules =
                attendancePolicyResolver.loadCompanyRules(companyId, toDate);

        ResolvedRange resolved = resolve(userId, fromDate, toDate, rule);

        Map<LocalDate, DailyAttendanceResponse> byDate = new HashMap<>();
        for (AttendanceWindowResolver.DayWindow window : resolved.days()) {
            LocalDate date = window.date();
            DailyAttendance record = stored.get(date);
            byDate.put(date, record != null
                    ? toDailyResponse(record)
                    : computeDay(employee, window, resolved.punchesOn(date), holidays, leaveDays,
                            rule, policyRules).day());
        }
        // A stored day whose roster row was deleted afterwards still exists, still
        // counts towards the month, and must not vanish from the day-by-day read
        // just because the roster no longer explains it.
        stored.forEach((date, record) -> byDate.putIfAbsent(date, toDailyResponse(record)));

        return byDate.values().stream()
                .sorted(Comparator.comparing(DailyAttendanceResponse::attendanceDate))
                .toList();
    }

    /**
     * The month's report. Reads the stored days once generated; otherwise
     * returns a preview computed from punches without persisting anything, so a
     * read can never overwrite a correction.
     */
    @Transactional(readOnly = true)
    public MonthlyAttendanceResponse getMonthlyAttendance(String userId, YearMonth month) {
        Employee employee = employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        List<DailyAttendance> stored = storedDays(userId, month);

        return stored.isEmpty() ? previewMonth(employee, month) : aggregateStored(employee, month, stored);
    }

    /** The stored attendance rows for a period. */
    @Transactional(readOnly = true)
    public List<AttendanceRecordResponse> getRecords(String userId, YearMonth month) {
        employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        return storedDays(userId, month).stream().map(AttendanceRecordResponse::of).toList();
    }

    /**
     * The summary payroll consumes. Refuses rather than silently deriving one,
     * because paying against attendance nobody generated is the failure this
     * whole feature exists to prevent.
     */
    @Transactional
    public MonthlyAttendanceSummary getGeneratedSummary(String userId, YearMonth month) {
        return getGeneratedSummary(employeeService.getEntityByUserId(userId), month);
    }

    /**
     * Same as {@link #getGeneratedSummary(String, YearMonth)}, for a caller
     * (e.g. {@code PayrollService.build}) that already resolved and
     * tenant-checked the target {@link Employee} in the same transaction -
     * skips the otherwise-redundant repeat lookup.
     */
    @Transactional
    public MonthlyAttendanceSummary getGeneratedSummary(Employee employee, YearMonth month) {
        String userId = employee.getUserId();
        if (storedDays(userId, month).isEmpty()) {
            throw new BusinessRuleException("Attendance has not been generated for " + userId
                    + " for " + month + " - generate and review it before running payroll");
        }
        return rebuildSummary(employee, month);
    }

    /**
     * Refreshes the cached summaries from the stored days. Employees with no
     * generated attendance are skipped rather than rebuilt from raw punches.
     */
    @Transactional
    public List<MonthlyAttendanceSummary> syncSummaries(YearMonth month) {
        return employeeService.getActiveEntities().stream()
                .filter(employee -> !storedDays(employee.getUserId(), month).isEmpty())
                .map(employee -> rebuildSummary(employee, month))
                .toList();
    }

    /**
     * Batched form of {@link #getDailyAttendance} for a single day across
     * many employees at once - reuses the identical per-employee
     * windowing/punch computation ({@link #windowedFrom},
     * {@link #computeFromPunches}) this class already uses for a
     * single-employee read, so the status for any one employee is
     * unchanged; only how the underlying data is fetched differs - a
     * handful of {@code IN}-clause queries for the whole batch instead of
     * the same half-dozen queries repeated once per employee. Built for
     * {@code DashboardService}'s "who was present" aggregates, previously
     * the single biggest source of redundant queries in the app (one
     * {@link #getDailyAttendance} call per employee per day).
     *
     * <p>Deliberately skips the per-target {@code assertSelfOrManages} check
     * {@link #getDailyAttendance} performs on every call - the caller is
     * expected to authorize the whole batch itself, once, up front (see
     * {@code DashboardService}, and SECURITY.md's note that Dashboard/Report
     * stay company-wide for SUPERVISOR/HR/ADMIN rather than self-service
     * scoped per record).
     */
    @Transactional(readOnly = true)
    public Map<String, AttendanceStatus> statusesOn(List<Employee> employees, LocalDate date) {
        if (employees.isEmpty()) {
            return Map.of();
        }
        List<String> userIds = employees.stream().map(Employee::getUserId).toList();

        Map<String, AttendanceStatus> result = new HashMap<>();
        Map<String, DailyAttendance> storedByUser = dailyAttendanceRepository
                .findAllByUserIdInAndAttendanceDate(userIds, date).stream()
                .collect(Collectors.toMap(DailyAttendance::getUserId, Function.identity()));
        storedByUser.forEach((userId, record) -> result.put(userId, record.getStatus()));

        List<Employee> needPreview = employees.stream()
                .filter(employee -> !storedByUser.containsKey(employee.getUserId()))
                .toList();
        if (needPreview.isEmpty()) {
            return result;
        }
        List<String> previewUserIds = needPreview.stream().map(Employee::getUserId).toList();

        Map<Long, CompanyGenerationContext> contextsByCompany = new HashMap<>();
        Map<String, List<ShiftSchedule>> rosterByUser = shiftScheduleRepository
                .findAllByUserIdInAndShiftDateBetween(previewUserIds, date.minusDays(1), date.plusDays(1))
                .stream()
                .collect(Collectors.groupingBy(ShiftSchedule::getUserId));
        rosterByUser.values().forEach(list -> list.sort(Comparator.comparing(ShiftSchedule::getShiftDate)));

        Map<String, LeaveCalculationService.LeaveDay> leaveByUser =
                leaveCalculationService.approvedLeaveDayOn(previewUserIds, date);

        // Each employee's own windows for this day and its neighbours, built by
        // the identical resolver the single-employee path uses - just fed a
        // pre-fetched, per-employee roster slice instead of querying one at a
        // time. The punch partition needs the neighbouring days present or the
        // previous night's shift would not be competing for its own exit punch.
        Map<String, List<AttendanceWindowResolver.DayWindow>> windowsByUser = new HashMap<>();
        for (Employee employee : needPreview) {
            String userId = employee.getUserId();
            Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            CompanyGenerationContext context = contextsByCompany.computeIfAbsent(companyId,
                    id -> resolveCompanyContext(id, date, date));
            List<AttendanceWindowResolver.DayWindow> windows = windowResolver
                    .windowsFor(rosterByUser.getOrDefault(userId, List.of()), context.rule());
            if (windows.stream().anyMatch(w -> w.date().equals(date))) {
                windowsByUser.put(userId, windows);
            }
            // No window on this date means not scheduled - left out of `result`
            // below exactly as an empty roster would leave isPresentOn false.
        }
        if (windowsByUser.isEmpty()) {
            return result;
        }

        // One punch fetch spanning every scheduled employee's own span for this
        // date, then partitioned per employee by the same resolver - the exact
        // ownership rule the per-employee path applies itself.
        LocalDateTime broadStart = windowsByUser.values().stream()
                .map(windowResolver::fetchFrom).min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime broadEnd = windowsByUser.values().stream()
                .map(windowResolver::fetchTo).max(LocalDateTime::compareTo).orElseThrow();
        Map<String, List<DeviceLog>> punchesByUser = deviceLogRepository
                .findAllByUserIdInAndLogDateBetweenOrderByLogDateAsc(
                        List.copyOf(windowsByUser.keySet()), broadStart, broadEnd)
                .stream()
                .collect(Collectors.groupingBy(DeviceLog::getUserId));

        for (Employee employee : needPreview) {
            String userId = employee.getUserId();
            List<AttendanceWindowResolver.DayWindow> windows = windowsByUser.get(userId);
            if (windows == null) {
                continue;
            }
            Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
            CompanyGenerationContext context = contextsByCompany.get(companyId);

            List<DeviceLog> punches = windowResolver.dedupe(
                    punchesByUser.getOrDefault(userId, List.of()).stream()
                            .sorted(Comparator.comparing(DeviceLog::getLogDate))
                            .toList());
            AttendanceWindowResolver.Resolution resolution = windowResolver.assign(windows, punches);

            AttendanceWindowResolver.DayWindow window = windows.stream()
                    .filter(w -> w.date().equals(date)).findFirst().orElseThrow();
            Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDaysForEmployee =
                    leaveByUser.containsKey(userId) ? Map.of(date, leaveByUser.get(userId)) : Map.of();

            AttendanceCalculationService.PolicyAwareDay computed = computeDay(employee, window,
                    resolution.punchesOn(date), context.holidays(), leaveDaysForEmployee,
                    context.rule(), context.policyRules());
            result.put(userId, computed.day().status());
        }

        return result;
    }

    // ---- internals ---------------------------------------------------------

    /**
     * One employee's roster and punches for a range, already partitioned.
     *
     * <p>The roster is read one day either side of the requested range so a
     * night shift on the last day of a month competes properly with the first
     * day of the next - and so the first day of the range knows whether the
     * previous night is still running. Only days inside the range are returned
     * in {@link ResolvedRange#days()}; the neighbours exist purely so the
     * partition is decided against the real roster rather than a truncated one.
     */
    private record ResolvedRange(List<AttendanceWindowResolver.DayWindow> days,
                                 AttendanceWindowResolver.Resolution resolution) {

        List<DeviceLog> punchesOn(LocalDate date) {
            return resolution.punchesOn(date);
        }
    }

    /** Resolves one employee's range: roster, punches, and who owns what. */
    private ResolvedRange resolve(String userId, LocalDate fromDate, LocalDate toDate, AttendanceRule rule) {
        List<ShiftSchedule> roster = shiftScheduleRepository
                .findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
                        userId, fromDate.minusDays(1), toDate.plusDays(1));
        return resolveFrom(roster, userId, fromDate, toDate, rule);
    }

    /**
     * Same as {@link #resolve}, given the roster already fetched - lets a
     * batched caller ({@link #statusesOn}) supply one IN-clause roster fetch
     * across many employees instead of one query per employee. {@code roster}
     * must be ordered ascending by {@code shiftDate}.
     *
     * <p>Punches are fetched <b>once</b> for the whole range and partitioned in
     * memory. The previous design issued one range query per rostered day -
     * around thirty per employee per month - and decided ownership by whether
     * those ranges happened to overlap. Partitioning makes "a punch belongs to
     * at most one day" true by construction, and collapses the query count to
     * one per employee.
     */
    private ResolvedRange resolveFrom(List<ShiftSchedule> roster, String userId,
                                      LocalDate fromDate, LocalDate toDate, AttendanceRule rule) {
        List<AttendanceWindowResolver.DayWindow> windows = windowResolver.windowsFor(roster, rule);
        if (windows.isEmpty()) {
            return new ResolvedRange(List.of(),
                    new AttendanceWindowResolver.Resolution(Map.of(), Set.of(), List.of()));
        }

        List<DeviceLog> punches = windowResolver.dedupe(deviceLogRepository
                .findAllByUserIdAndLogDateBetweenOrderByLogDateAsc(
                        userId, windowResolver.fetchFrom(windows), windowResolver.fetchTo(windows)));

        return finishResolve(windows, punches, fromDate, toDate);
    }

    /** Shared tail of both resolve paths: partition, then keep only the days in range. */
    private ResolvedRange finishResolve(List<AttendanceWindowResolver.DayWindow> windows,
                                        List<DeviceLog> punches, LocalDate fromDate, LocalDate toDate) {
        AttendanceWindowResolver.Resolution resolution = windowResolver.assign(windows, punches);

        List<AttendanceWindowResolver.DayWindow> inRange = windows.stream()
                .filter(w -> !w.date().isBefore(fromDate) && !w.date().isAfter(toDate))
                .toList();

        if (!resolution.conflictDates().isEmpty()) {
            log.warn("attendance.roster-conflict dates={} - consecutive shifts overlap, so nobody "
                    + "could have worked both. Punches were assigned to the earlier shift date.",
                    resolution.conflictDates());
        }
        if (!resolution.unassigned().isEmpty()) {
            log.info("attendance.unassigned-punches count={} - punches inside no rostered day's window",
                    resolution.unassigned().size());
        }
        return new ResolvedRange(inRange, resolution);
    }

    /**
     * One day of attendance derived from the punches that day owns, with this
     * employee's day-scoped policy applied.
     *
     * <p>The policy is resolved per <b>date</b>, not once per employee: a rule
     * version effective mid-month must apply to the days after it and not the
     * days before, which is what stops a rule written in September re-pricing
     * August. {@code policyRules} is the whole company's rule set, loaded once
     * per generation run, so resolving per day costs no queries.
     */
    private AttendanceCalculationService.PolicyAwareDay computeDay(
            Employee employee, AttendanceWindowResolver.DayWindow window,
            List<DeviceLog> punches, Set<LocalDate> holidays,
            Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays,
            AttendanceRule rule, List<AttendancePolicyRule> policyRules) {
        ShiftSchedule schedule = window.schedule();
        LocalDate date = schedule.getShiftDate();

        ResolvedPolicy policy = attendancePolicyResolver.resolve(policyRules, employee, date);

        return attendanceCalculationService.calculateDay(employee.getUserId(), date, schedule.getShift(),
                punches, schedule.isWeekOff(), holidays.contains(date), leaveDays.containsKey(date),
                rule, policy);
    }

    private void applyComputed(DailyAttendance record, String userId, ShiftSchedule schedule,
                               DailyAttendanceResponse computed, boolean holiday) {
        record.setUserId(userId);
        record.setAttendanceDate(schedule.getShiftDate());
        record.setShiftCode(schedule.getShift().getShiftCode());
        record.setFirstIn(computed.firstIn());
        record.setLastOut(computed.lastOut());
        record.setWorkingHours(computed.workingHours());
        record.setBreakHours(computed.breakHours());
        record.setOvertimeHours(computed.overtimeHours());
        record.setLateMinutes(computed.lateMinutes());
        record.setEarlyExitMinutes(computed.earlyExitMinutes());
        record.setInvalidPunch(computed.invalidPunch());
        record.setWeekOff(schedule.isWeekOff());
        record.setHoliday(holiday);
        record.setStatus(computed.status());
    }

    private MonthlyAttendanceResponse previewMonth(Employee employee, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        AttendanceRule rule = attendanceRuleService.getActiveRuleForCompany(companyId);
        Set<LocalDate> holidays = holidayService.mandatoryHolidayDates(companyId, first, last);
        Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays =
                leaveCalculationService.approvedLeaveDaysBetween(employee.getUserId(), first, last);

        List<AttendancePolicyRule> policyRules =
                attendancePolicyResolver.loadCompanyRules(companyId, last);

        ResolvedRange resolved = resolve(employee.getUserId(), first, last, rule);
        List<AttendanceWindowResolver.DayWindow> roster = resolved.days();

        List<DailyAttendanceResponse> days = new ArrayList<>(roster.size());
        Set<LocalDate> workingDates = new HashSet<>();
        // A preview persists nothing, so the trace it needs for the month-scoped
        // rules is the one it just computed in memory rather than the stored one.
        Set<LocalDate> latePenalised = new HashSet<>();
        BigDecimal compOff = BigDecimal.ZERO;
        for (AttendanceWindowResolver.DayWindow window : roster) {
            LocalDate date = window.date();
            AttendanceCalculationService.PolicyAwareDay computed = computeDay(employee, window,
                    resolved.punchesOn(date), holidays, leaveDays, rule, policyRules);
            days.add(computed.day());
            computed.trace().stream()
                    .filter(row -> row.getRuleType() == com.accusharp.hrms.enums.RuleType.LATE_ARRIVAL)
                    .forEach(row -> latePenalised.add(row.getAttendanceDate()));
            compOff = compOff.add(computed.compOffCredit());
            if (!window.schedule().isWeekOff() && !holidays.contains(date)) {
                workingDates.add(date);
            }
        }

        long holidayDays = roster.stream().filter(w -> holidays.contains(w.date())).count();
        long weekOffDays = roster.stream().filter(w -> w.schedule().isWeekOff()).count();

        return aggregate(employee, month, days, workingDates, leaveDays, holidayDays, weekOffDays,
                latePenalised, compOff.setScale(DAY_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * Rolls up the stored days. Week-off and holiday counts come from the
     * snapshotted flags rather than the status, because working a holiday
     * yields {@code PRESENT} - the day is still a holiday.
     */
    private MonthlyAttendanceResponse aggregateStored(Employee employee, YearMonth month,
                                                      List<DailyAttendance> stored) {
        return aggregate(employee, month,
                stored.stream().map(this::toDailyResponse).toList(),
                workingDatesOf(stored),
                leaveCalculationService.approvedLeaveDaysInMonth(employee.getUserId(), month),
                stored.stream().filter(DailyAttendance::isHoliday).count(),
                stored.stream().filter(DailyAttendance::isWeekOff).count(),
                latePenalisedDates(employee.getUserId(), month),
                compOffCreditDays(employee.getUserId(), month));
    }

    /** Rolls the stored days up and writes the cached summary. */
    private MonthlyAttendanceSummary rebuildSummary(Employee employee, YearMonth month) {
        String userId = employee.getUserId();
        MonthlyAttendanceResponse rolled = aggregateStored(employee, month, storedDays(userId, month));

        String monthKey = month.toString();
        MonthlyAttendanceSummary summary = monthlyAttendanceSummaryRepository
                .findByUserIdAndMonth(userId, monthKey)
                .orElseGet(() -> MonthlyAttendanceSummary.builder().userId(userId).month(monthKey).build());

        summary.setWorkingDays(rolled.workingDays());
        summary.setPresentDays(rolled.presentDays());
        summary.setAbsentDays(rolled.absentDays());
        summary.setHalfDays(rolled.halfDays());
        summary.setLeaveDays(rolled.leaveDays());
        summary.setHolidayDays(rolled.holidayDays());
        summary.setWeekOffDays(rolled.weekOffDays());
        summary.setLateCount(rolled.lateCount());
        summary.setEarlyExitCount(rolled.earlyExitCount());
        summary.setInvalidPunches(rolled.invalidPunches());
        summary.setTotalHours(rolled.totalHours());
        summary.setOvertimeHours(rolled.overtimeHours());
        summary.setLopDays(rolled.lopDays());
        summary.setPolicyLopDays(rolled.policyLopDays());
        summary.setCompOffCreditDays(rolled.compOffCreditDays());

        // Replaced wholesale, never merged: the outcomes are derived from a
        // replay that starts at a zero accumulator, so a rebuild must leave
        // exactly what this replay concluded and nothing from the last one.
        policyOutcomeRepository.deleteByUserIdAndMonth(userId, monthKey);
        if (!rolled.policyOutcomes().isEmpty()) {
            policyOutcomeRepository.saveAll(rolled.policyOutcomes());
        }

        return monthlyAttendanceSummaryRepository.save(summary);
    }

    /** The one place month-level figures are derived, stored or preview. */
    private MonthlyAttendanceResponse aggregate(Employee employee, YearMonth month,
                                                List<DailyAttendanceResponse> days,
                                                Set<LocalDate> workingDates,
                                                Map<LocalDate, LeaveCalculationService.LeaveDay> leaveDays,
                                                long holidayDays, long weekOffDays,
                                                Set<LocalDate> latePenalisedDates,
                                                BigDecimal compOffCreditDays) {

        long workingDays = workingDates.size();

        BigDecimal presentDays = days.stream()
                .filter(day -> workingDates.contains(day.attendanceDate()))
                .map(day -> attendanceCalculationService.dayFraction(day.status()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(DAY_SCALE, RoundingMode.HALF_UP);

        BigDecimal leaveDayCount = leaveCalculationService.totalLeaveDays(leaveDays, workingDates);
        BigDecimal paidLeaveDays = leaveCalculationService.paidLeaveDays(leaveDays, workingDates);

        BigDecimal absentDays = BigDecimal.valueOf(workingDays)
                .subtract(presentDays)
                .subtract(leaveDayCount)
                .max(BigDecimal.ZERO)
                .setScale(DAY_SCALE, RoundingMode.HALF_UP);

        long halfDays = days.stream().filter(day -> day.status() == AttendanceStatus.HALF_DAY).count();
        long lateCount = days.stream().filter(day -> day.lateMinutes() > 0).count();
        long earlyExitCount = days.stream().filter(day -> day.earlyExitMinutes() > 0).count();
        long invalidPunches = days.stream().filter(DailyAttendanceResponse::invalidPunch).count();

        BigDecimal totalHours = sumHours(days, DailyAttendanceResponse::workingHours);
        BigDecimal overtimeHours = sumHours(days, DailyAttendanceResponse::overtimeHours);

        BigDecimal baseLop = lopCalculationService.calculateLopDays(
                BigDecimal.valueOf(workingDays), presentDays, paidLeaveDays);

        // Month-scoped rules replay HERE rather than in rebuildSummary, because
        // rebuildSummary and the read path both funnel through this method.
        // Hooking only the persisting one would make GET /{userId}/monthly
        // disagree with the summary payroll pays from, by exactly the penalty.
        MonthPolicyEvaluator.MonthPolicyResult policyResult = monthPolicyEvaluator.apply(
                employee.getUserId(), month, days, workingDates, latePenalisedDates,
                monthPolicyFor(employee, month));

        // Clamped: a badly-configured accumulation rule could otherwise push LOP
        // past the days the employee was expected to work. calculatePayableDays
        // already floors payable days at zero, but a slip reading "22 working
        // days, 31 LOP days" is not something to print.
        BigDecimal lopDays = baseLop.add(policyResult.lopDays())
                .min(BigDecimal.valueOf(workingDays))
                .setScale(DAY_SCALE, RoundingMode.HALF_UP);

        return new MonthlyAttendanceResponse(employee.getUserId(), employee.getEmployeeName(), month,
                workingDays, presentDays, absentDays, halfDays, leaveDayCount, holidayDays, weekOffDays,
                lateCount, earlyExitCount, invalidPunches, totalHours, overtimeHours, lopDays,
                policyResult.lopDays(), compOffCreditDays, policyResult.outcomes(), days);
    }

    /**
     * The month-scoped policy for one employee, resolved on the <b>first</b> day
     * of the month.
     *
     * <p>Not the last, and not per day. A budget or an occurrence counter is a
     * property of the month as a whole, so it needs one version for the whole
     * month; resolving on the month's end would let a rule created on the 28th
     * retroactively re-judge the preceding 27 days, which is precisely the
     * surprise effective dating exists to prevent. Resolving on the 1st means a
     * month-scoped rule takes effect from the first full month on or after its
     * {@code effectiveFrom} - predictable, and it can be explained to an
     * employee in one sentence: the budget for September was the one set before
     * September began.
     */
    private ResolvedPolicy monthPolicyFor(Employee employee, YearMonth month) {
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();
        LocalDate firstOfMonth = month.atDay(1);
        return attendancePolicyResolver.resolve(
                attendancePolicyResolver.loadCompanyRules(companyId, firstOfMonth),
                employee, firstOfMonth);
    }

    /** Comp-off days the month's stored days earned under a {@code DAY_OFF_WORK} rule. */
    private BigDecimal compOffCreditDays(String userId, YearMonth month) {
        return policyApplicationRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        userId, month.atDay(1), month.atEndOfMonth())
                .stream()
                .map(AttendancePolicyApplication::getCompOffCredit)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(DAY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Days a {@code LATE_ARRIVAL} rule already downgraded, read off the stored
     * trace. Excluded from the late-mark count so one late arrival is never
     * charged twice - see {@link MonthPolicyEvaluator#apply}.
     */
    private Set<LocalDate> latePenalisedDates(String userId, YearMonth month) {
        return policyApplicationRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        userId, month.atDay(1), month.atEndOfMonth())
                .stream()
                .filter(row -> row.getRuleType() == com.accusharp.hrms.enums.RuleType.LATE_ARRIVAL)
                .map(AttendancePolicyApplication::getAttendanceDate)
                .collect(Collectors.toSet());
    }

    private List<DailyAttendance> storedDays(String userId, YearMonth month) {
        return dailyAttendanceRepository.findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                userId, month.atDay(1), month.atEndOfMonth());
    }

    /**
     * Expected working days read off the stored rows rather than re-read from
     * the roster - a frozen month must not shift because someone edited the
     * roster or the holiday calendar afterwards.
     */
    private Set<LocalDate> workingDatesOf(List<DailyAttendance> stored) {
        return stored.stream()
                .filter(DailyAttendance::isWorkingDay)
                .map(DailyAttendance::getAttendanceDate)
                .collect(Collectors.toSet());
    }

    private DailyAttendanceResponse toDailyResponse(DailyAttendance record) {
        return new DailyAttendanceResponse(record.getUserId(), record.getAttendanceDate(),
                record.getShiftCode(), record.getFirstIn(), record.getLastOut(), record.getWorkingHours(),
                record.getBreakHours(), record.getOvertimeHours(), record.getLateMinutes(),
                record.getEarlyExitMinutes(), record.isInvalidPunch(), record.getStatus());
    }

    private List<Employee> resolveEmployees(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return employeeService.getActiveEntities();
        }
        return userIds.stream().map(employeeService::getEntityByUserId).toList();
    }

    private Map<LocalDate, DailyAttendance> indexByDate(List<DailyAttendance> records) {
        Map<LocalDate, DailyAttendance> index = new HashMap<>();
        records.forEach(record -> index.put(record.getAttendanceDate(), record));
        return index;
    }

    private void assertHrOrAdmin(String actorId) {
        Employee actor = employeeService.getEntityByUserId(actorId);
        if (actor.getRole() != Role.HR && actor.getRole() != Role.ADMIN) {
            throw new BusinessRuleException(
                    "Generating or correcting attendance requires the HR or ADMIN role");
        }
    }

    private BigDecimal sumHours(List<DailyAttendanceResponse> days,
                                Function<DailyAttendanceResponse, BigDecimal> extractor) {
        return days.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(HOUR_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal zeroHours() {
        return BigDecimal.ZERO.setScale(HOUR_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * What one employee's generation run did.
     *
     * @param rosterDays        days of the period the roster actually knew about -
     *                          zero is what makes an employee appear in
     *                          {@code employeesWithoutRoster}, and it deliberately
     *                          stays a count of <em>rostered</em> days so filling
     *                          the gaps does not hide the fact that there was no
     *                          roster to begin with
     * @param generated         days written, rostered and unrostered together
     * @param unrosteredGenerated the subset of {@code generated} that had no shift
     */
    private record GenerationTally(int rosterDays, int generated, int unrosteredGenerated,
                                   int manualPreserved, int lockedSkipped,
                                   List<AttendanceGenerationResponse.DayChange> changes) {
    }

    /**
     * The attendance rule, holiday calendar and policy rule set for one company,
     * resolved once per {@link #generate}.
     */
    private record CompanyGenerationContext(AttendanceRule rule, Set<LocalDate> holidays,
                                            List<AttendancePolicyRule> policyRules) {
    }
}
