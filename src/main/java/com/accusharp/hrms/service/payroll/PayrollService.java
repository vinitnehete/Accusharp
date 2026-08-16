package com.accusharp.hrms.service.payroll;

import com.accusharp.hrms.dto.PayrollDebugRow;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.DeductionCalculationService;
import com.accusharp.hrms.service.calculation.LopCalculationService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The final aggregation layer. Payroll consumes employee master data,
 * attendance, leave, the holiday calendar, the shift roster and the salary
 * rules, and writes one immutable record per employee per period.
 *
 * <p>Immutability is real: regenerating does not overwrite. The existing row is
 * marked {@link PayrollStatus#SUPERSEDED} and a new revision is inserted, so a
 * salary slip printed last month can always be reproduced exactly.
 *
 * <p>Two payment models are supported:
 * <ul>
 *   <li><b>DAY_WISE</b> - paid against attended days over a fixed payable-day
 *       base (26 by default), with overtime on hours beyond the standard day.</li>
 *   <li><b>everyone else</b> - salaried against the full calendar month
 *       (week-offs included, unlike DAY_WISE), reduced only by whatever LOP
 *       the generated attendance found. Overtime is still computed and shown
 *       as its own line, never part of this proration.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollService {

    private static final int SCALE = SalaryCalculationService.SCALE;

    private final PayrollRepository payrollRepository;
    private final EmployeeService employeeService;
    private final SalaryRuleService salaryRuleService;
    private final AttendanceService attendanceService;
    private final SalaryCalculationService salaryCalculationService;
    private final DeductionCalculationService deductionCalculationService;
    private final LopCalculationService lopCalculationService;
    private final AuditService auditService;

    /**
     * Generates the period once; a second call is a conflict.
     *
     * <p>Tenant-checks {@code employeeId} before the existence check below,
     * not after - otherwise a cross-company id's 409-vs-404 would leak
     * whether that other company already generated payroll for this period,
     * a one-bit information leak {@code build()}'s own (later) check
     * wouldn't have prevented.
     */
    @Transactional
    public Payroll generate(PayrollRequest request) {
        employeeService.getEntityByUserId(request.getEmployeeId());
        payrollRepository.findByEmployeeIdAndMonthAndYearAndStatus(
                        request.getEmployeeId(), request.getMonth(), request.getYear(), PayrollStatus.GENERATED)
                .ifPresent(existing -> {
                    throw new ConflictException("Payroll already generated for " + request.getEmployeeId()
                            + " for " + request.getMonth() + "/" + request.getYear()
                            + " - use the regenerate endpoint");
                });
        return build(request, 1);
    }

    /**
     * Recomputes the period from current data. The previous revision is kept
     * and marked superseded rather than edited.
     *
     * <p>Tenant-checks {@code employeeId} before touching {@code current} -
     * otherwise a cross-company id would get marked {@link
     * PayrollStatus#SUPERSEDED} and persisted before {@code build()}'s own
     * check ever ran, saved from actually happening only by this whole
     * method being one transaction that then rolls back - correct by
     * accident, not by design.
     */
    @Transactional
    public Payroll regenerate(PayrollRequest request) {
        employeeService.getEntityByUserId(request.getEmployeeId());
        Payroll current = payrollRepository.findByEmployeeIdAndMonthAndYearAndStatus(
                        request.getEmployeeId(), request.getMonth(), request.getYear(), PayrollStatus.GENERATED)
                .orElseThrow(() -> NotFoundException.of("Payroll",
                        request.getEmployeeId() + " " + request.getMonth() + "/" + request.getYear()));

        current.setStatus(PayrollStatus.SUPERSEDED);
        payrollRepository.save(current);

        log.info("payroll.regenerate employeeId={} period={}/{} previousRevision={}",
                request.getEmployeeId(), request.getMonth(), request.getYear(), current.getRevision());
        return build(request, current.getRevision() + 1);
    }

    /** Runs payroll for every active employee, skipping periods already done. */
    @Transactional
    public List<Payroll> generateForAll(int month, int year, String generatedBy) {
        return employeeService.getActiveEntities().stream()
                .filter(employee -> payrollRepository.findByEmployeeIdAndMonthAndYearAndStatus(
                        employee.getUserId(), month, year, PayrollStatus.GENERATED).isEmpty())
                .map(employee -> {
                    PayrollRequest request = new PayrollRequest();
                    request.setEmployeeId(employee.getUserId());
                    request.setMonth(month);
                    request.setYear(year);
                    request.setGeneratedBy(generatedBy);
                    return build(request, 1);
                })
                .toList();
    }

    /**
     * Every read below resolves the owning employee through
     * {@code EmployeeService} before returning anything - that is where
     * tenant isolation actually lives (see {@code EmployeeService}'s
     * Javadoc), so a numeric payroll id or an employee id string from
     * another company yields the identical 404 an unknown one would.
     */
    @Transactional(readOnly = true)
    public Payroll getById(Long id) {
        Payroll payroll = payrollRepository.findById(id).orElseThrow(() -> NotFoundException.of("Payroll", id));
        employeeService.getEntityByUserId(payroll.getEmployeeId());
        employeeService.assertSelfOrManages(payroll.getEmployeeId());
        return payroll;
    }

    @Transactional(readOnly = true)
    public Payroll getCurrent(String employeeId, int month, int year) {
        employeeService.getEntityByUserId(employeeId);
        employeeService.assertSelfOrManages(employeeId);
        return payrollRepository.findByEmployeeIdAndMonthAndYearAndStatus(
                        employeeId, month, year, PayrollStatus.GENERATED)
                .orElseThrow(() -> NotFoundException.of("Payroll", employeeId + " " + month + "/" + year));
    }

    @Transactional(readOnly = true)
    public List<Payroll> getRevisions(String employeeId, int month, int year) {
        employeeService.getEntityByUserId(employeeId);
        employeeService.assertSelfOrManages(employeeId);
        return payrollRepository.findAllByEmployeeIdAndMonthAndYearOrderByRevisionDesc(employeeId, month, year);
    }

    @Transactional(readOnly = true)
    public List<Payroll> getHistory(String employeeId) {
        employeeService.getEntityByUserId(employeeId);
        employeeService.assertSelfOrManages(employeeId);
        return payrollRepository.findAllByEmployeeIdOrderByYearDescMonthDesc(employeeId);
    }

    /**
     * Scoped to the caller's own company - {@code Payroll} has no
     * {@code company_id} column of its own (only a denormalized
     * {@code companyName} string), so this filters by the caller's
     * company's employee {@code userId}s instead, via {@link
     * EmployeeService#getAllEntities()} rather than {@link
     * EmployeeService#getActiveEntities()}: payroll history for a
     * since-deactivated employee must stay visible to their own company's
     * reports.
     */
    @Transactional(readOnly = true)
    public List<Payroll> getPeriod(int month, int year) {
        List<Payroll> period = payrollRepository.findAllByMonthAndYearAndStatus(month, year, PayrollStatus.GENERATED);
        Set<String> companyUserIds = employeeService.getAllEntities().stream()
                .map(Employee::getUserId)
                .collect(Collectors.toSet());
        return period.stream().filter(payroll -> companyUserIds.contains(payroll.getEmployeeId())).toList();
    }

    /**
     * Same company scoping as {@link #getPeriod}, plus self-service
     * restriction on top - for raw individual-record list/export endpoints
     * ({@code PayrollController.getPeriod}, {@code SalarySlipService}'s
     * period methods), not for {@code ReportService}'s aggregate reports,
     * which deliberately stay company-wide for SUPERVISOR/HR/ADMIN (see
     * SECURITY.md's self-service scoping note for why the two are treated
     * differently) - so {@link #getPeriod} itself is intentionally left
     * unrestricted and every {@code ReportService} caller keeps using it
     * directly.
     */
    @Transactional(readOnly = true)
    public List<Payroll> getPeriodForCaller(int month, int year) {
        return getPeriod(month, year).stream()
                .filter(payroll -> employeeService.isSelfOrManages(payroll.getEmployeeId()))
                .toList();
    }

    /**
     * Same scope as {@link #getPeriodForCaller}, but every field the
     * calculation consumed and produced, plus a live re-read of the
     * employee's master salary data and the company's current {@code
     * SalaryRule} - so a wrong number can be traced to its cause (bad
     * attendance input, a stale rule the payroll predates, a rule or salary
     * edited after generation) without recomputing anything by hand. See
     * {@link PayrollDebugRow}.
     */
    @Transactional(readOnly = true)
    public List<PayrollDebugRow> getPeriodDebugForCaller(int month, int year) {
        List<Payroll> period = getPeriodForCaller(month, year);
        Map<String, Employee> employeesByUserId = employeeService.getAllEntities().stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity(), (a, b) -> a));

        return period.stream()
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> toDebugRow(payroll, employeesByUserId.get(payroll.getEmployeeId())))
                .toList();
    }

    private PayrollDebugRow toDebugRow(Payroll p, Employee employee) {
        BigDecimal liveGrossSalary = employee == null ? null : employee.getGrossSalary();
        BigDecimal livePfBasic = employee == null ? null : employee.getPfBasic();
        SalaryRule liveRule = employee == null ? null
                : salaryRuleService.getActiveRuleForCompany(employee.getCompany());

        boolean masterDataDrifted = employee != null
                && (differs(p.getGrossSalary(), liveGrossSalary) || differs(p.getPfBasic(), livePfBasic));
        boolean ruleDrifted = liveRule != null
                && (differs(p.getRuleBasicDaPercent(), liveRule.getBasicDaPercent())
                    || differs(p.getRulePfPercent(), liveRule.getPfPercent())
                    || differs(p.getRuleEsicPercent(), liveRule.getEsicPercent()));

        return new PayrollDebugRow(
                p.getId(), p.getEmployeeId(), p.getEmployeeName(), p.getEmployeeCode(), p.getCompanyName(),
                p.getDepartmentName(), p.getDesignationName(),
                p.getEmploymentStatus() == null ? null : p.getEmploymentStatus().name(),
                p.getRevision(), p.getStatus() == null ? null : p.getStatus().name(),
                p.getGeneratedAt() == null ? null : p.getGeneratedAt().toString(), p.getGeneratedBy(),

                p.getDaysInMonth(), p.getWorkingDays(), p.getPresentDays(), p.getPaidLeaveDays(), p.getLopDays(),
                p.getPayableDays(), p.getTotalHours(), p.getOvertimeHours(), p.getPerDay(), p.getPerHour(),

                p.getEarnBasicDA(), p.getEarnHra(), p.getEarnConveyance(), p.getEarnEducation(), p.getEarnMedical(),
                p.getEarnOther(), p.getBonus(), p.getIncentive(), p.getOtAllowance(), p.getEarnGrossSalary(),
                p.getTotalEarnings(),

                p.getPf(), p.getEarnPf(), p.getPfDeduction(), p.getEsic(), p.getProfessionalTax(), p.getMlwf(),
                p.getTds(), p.getAdvanceDeduction(), p.getLoanDeduction(), p.getCanteen(), p.getLopDeduction(),
                p.getTotalDeduction(), p.getNetSalary(),

                p.getGrossSalary(), liveGrossSalary, p.getPfBasic(), livePfBasic,

                p.getRuleBasicDaPercent(), liveRule == null ? null : liveRule.getBasicDaPercent(),
                p.getRulePfPercent(), liveRule == null ? null : liveRule.getPfPercent(),
                p.getRuleEsicPercent(), liveRule == null ? null : liveRule.getEsicPercent(),

                masterDataDrifted, ruleDrifted);
    }

    private static boolean differs(BigDecimal stored, BigDecimal live) {
        if (stored == null || live == null) {
            return false;
        }
        return stored.compareTo(live) != 0;
    }

    // ---- the calculation ---------------------------------------------------

    private Payroll build(PayrollRequest request, int revision) {
        Employee employee = employeeService.getEntityByUserId(request.getEmployeeId());
        // The employee's own company's rule, not the caller's - correct regardless of who is asking.
        SalaryRule rule = salaryRuleService.getActiveRuleForCompany(employee.getCompany());
        YearMonth period = YearMonth.of(request.getYear(), request.getMonth());

        // Pay from the attendance HR generated and reviewed - never from a
        // fresh recompute, which would discard their corrections. Refuses
        // outright if the period was never generated.
        MonthlyAttendanceSummary attendance =
                attendanceService.getGeneratedSummary(employee.getUserId(), period);

        Payroll payroll = new Payroll();
        payroll.setEmployeeId(employee.getUserId());
        payroll.setMonth(request.getMonth());
        payroll.setYear(request.getYear());
        payroll.setRevision(revision);
        payroll.setStatus(PayrollStatus.GENERATED);

        snapshotEmployee(payroll, employee);
        snapshotRule(payroll, rule);

        boolean dayWise = employee.getStatus().isPaidPerAttendedDay();

        // Base of the proration: the denominator every earning is divided by.
        // DAY_WISE is paid per attended day against a fixed payable-day base;
        // everyone else is salaried against the full calendar month - week-offs
        // included, reduced only by whatever LOP the generated attendance found.
        BigDecimal totalDays = dayWise
                ? BigDecimal.valueOf(rule.getDayWiseDaysInMonth())
                : BigDecimal.valueOf(period.lengthOfMonth());

        BigDecimal presentDays = attendance.getPresentDays();
        BigDecimal paidLeaveDays = paidLeaveDays(attendance);

        BigDecimal lopDays;
        BigDecimal payableDays;
        if (dayWise) {
            // Attendance is the pay: no LOP concept, you are paid what you worked.
            lopDays = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
            payableDays = presentDays.add(paidLeaveDays).min(totalDays);
        } else {
            lopDays = attendance.getLopDays();
            payableDays = lopCalculationService.calculatePayableDays(totalDays, lopDays);
        }

        payroll.setDaysInMonth(period.lengthOfMonth());
        payroll.setWorkingDays((int) attendance.getWorkingDays());
        payroll.setPresentDays(presentDays);
        payroll.setPaidLeaveDays(paidLeaveDays);
        payroll.setLopDays(lopDays);
        payroll.setPayableDays(payableDays);
        payroll.setTotalHours(attendance.getTotalHours());
        // DAY_WISE has no fixed daily shift to measure each day's overtime
        // against - only a fixed monthly expectation (dayWiseDaysInMonth *
        // standardHoursPerDay) the month's total hours are compared to.
        // Everyone else keeps the attendance engine's daily-summed value
        // (each day measured against that day's own shift length).
        BigDecimal overtimeHours = dayWise
                ? monthlyOvertimeHours(attendance.getTotalHours(), rule)
                : attendance.getOvertimeHours();
        payroll.setOvertimeHours(overtimeHours);

        // ---- earnings ------------------------------------------------------
        payroll.setEarnBasicDA(salaryCalculationService.prorate(employee.getBasicDA(), totalDays, payableDays));
        payroll.setEarnHra(salaryCalculationService.prorate(employee.getHra(), totalDays, payableDays));
        payroll.setEarnConveyance(salaryCalculationService.prorate(
                employee.getConveyanceAllowance(), totalDays, payableDays));
        payroll.setEarnEducation(salaryCalculationService.prorate(
                employee.getEducationAllowance(), totalDays, payableDays));
        payroll.setEarnMedical(salaryCalculationService.prorate(
                employee.getMedicalAllowance(), totalDays, payableDays));
        payroll.setEarnOther(salaryCalculationService.prorate(
                employee.getOtherAllowance(), totalDays, payableDays));

        BigDecimal earnGross = deductionCalculationService.sum(
                payroll.getEarnBasicDA(), payroll.getEarnHra(), payroll.getEarnConveyance(),
                payroll.getEarnEducation(), payroll.getEarnMedical(), payroll.getEarnOther());
        payroll.setEarnGrossSalary(earnGross);

        BigDecimal perDay = salaryCalculationService.divide(employee.getGrossSalary(), totalDays);
        BigDecimal perHour = salaryCalculationService.divide(perDay, rule.getStandardHoursPerDay());
        payroll.setPerDay(perDay);
        payroll.setPerHour(perHour);
        payroll.setOtAllowance(overtimeAllowance(employee, overtimeHours, perHour, rule));

        payroll.setBonus(salaryCalculationService.scaled(request.getBonus()));
        payroll.setIncentive(salaryCalculationService.scaled(request.getIncentive()));

        BigDecimal totalEarnings = deductionCalculationService.sum(
                earnGross, payroll.getBonus(), payroll.getIncentive(), payroll.getOtAllowance());
        payroll.setTotalEarnings(totalEarnings);

        // ---- deductions ----------------------------------------------------
        BigDecimal earnPf = deductionCalculationService.calculateEarnPf(
                employee.getPfBasic(), totalDays, payableDays);
        payroll.setPf(deductionCalculationService.calculatePf(employee.getPfBasic(), rule));
        payroll.setEarnPf(earnPf);
        payroll.setPfDeduction(deductionCalculationService.calculatePfDeduction(earnPf, rule));
        payroll.setEsic(deductionCalculationService.calculateEsic(earnGross, rule));
        payroll.setProfessionalTax(
                deductionCalculationService.calculateProfessionalTax(employee.getGrossSalary(), rule));
        payroll.setMlwf(deductionCalculationService.calculateMlwf(request.getMonth(), rule));
        payroll.setTds(salaryCalculationService.scaled(request.getTds()));
        payroll.setAdvanceDeduction(salaryCalculationService.scaled(request.getAdvanceDeduction()));
        payroll.setLoanDeduction(salaryCalculationService.scaled(request.getLoanDeduction()));
        payroll.setCanteen(salaryCalculationService.scaled(request.getCanteen()));

        // Reported for transparency only - the earnings above were already
        // prorated down by the same days, so adding it would deduct twice.
        payroll.setLopDeduction(deductionCalculationService.calculateLopDeduction(
                employee.getGrossSalaryWage(), totalDays, lopDays));

        BigDecimal totalDeduction = deductionCalculationService.sum(
                payroll.getPfDeduction(), payroll.getEsic(), payroll.getProfessionalTax(), payroll.getMlwf(),
                payroll.getTds(), payroll.getAdvanceDeduction(), payroll.getLoanDeduction(), payroll.getCanteen());
        payroll.setTotalDeduction(totalDeduction);

        payroll.setNetSalary(totalEarnings.subtract(totalDeduction).setScale(SCALE, RoundingMode.HALF_UP));

        payroll.setGeneratedAt(Instant.now());
        payroll.setGeneratedBy(request.getGeneratedBy());

        log.info("payroll.generate employeeId={} period={}/{} revision={} payableDays={} net={}",
                payroll.getEmployeeId(), payroll.getMonth(), payroll.getYear(), revision,
                payableDays, payroll.getNetSalary());

        Payroll saved = payrollRepository.save(payroll);
        auditService.record("PAYROLL_GENERATE", "Payroll", employee.getUserId(), AuditOutcome.SUCCESS,
                "period=" + payroll.getMonth() + "/" + payroll.getYear() + " revision=" + revision);

        // Freeze the attendance this payroll was computed from, so the slip
        // stays reproducible. Correcting it later means unlock, fix, regenerate.
        attendanceService.lockMonth(employee.getUserId(), period);

        return saved;
    }

    /**
     * Overtime is paid only to eligible employees, on whichever
     * {@code overtimeHours} figure this employee's status uses - the
     * attendance engine's daily-summed value for everyone salaried, or
     * {@link #monthlyOvertimeHours} for DAY_WISE (see {@link #build}).
     */
    private BigDecimal overtimeAllowance(Employee employee, BigDecimal overtimeHours,
                                         BigDecimal perHour, SalaryRule rule) {
        if (!employee.isOvertimeEligible() || overtimeHours == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return overtimeHours
                .multiply(perHour)
                .multiply(rule.getOvertimeRateMultiplier())
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * DAY_WISE overtime against the fixed monthly base - {@code
     * dayWiseDaysInMonth * standardHoursPerDay} (208h at the defaults) - not
     * the sum of each day's own shift overtime a day-wise worker has no
     * fixed daily shift to measure against.
     */
    private BigDecimal monthlyOvertimeHours(BigDecimal totalHours, SalaryRule rule) {
        BigDecimal baseHours = BigDecimal.valueOf(rule.getDayWiseDaysInMonth())
                .multiply(rule.getStandardHoursPerDay());
        return totalHours.subtract(baseHours).max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The summary records total leave days; the paid share is what offsets LOP.
     * Unpaid leave is already excluded from the LOP figure the summary carries,
     * so deriving it back keeps the two consistent.
     */
    private BigDecimal paidLeaveDays(MonthlyAttendanceSummary attendance) {
        BigDecimal implied = BigDecimal.valueOf(attendance.getWorkingDays())
                .subtract(attendance.getPresentDays())
                .subtract(attendance.getLopDays())
                .max(BigDecimal.ZERO);
        return implied.min(attendance.getLeaveDays()).setScale(1, RoundingMode.HALF_UP);
    }

    private void snapshotEmployee(Payroll payroll, Employee employee) {
        payroll.setEmployeeName(employee.getEmployeeName());
        payroll.setEmployeeCode(employee.getEmployeeCode());
        payroll.setCompanyName(employee.getCompany() == null ? null : employee.getCompany().getCompanyName());
        payroll.setDepartmentName(employee.getDepartment() == null ? null
                : employee.getDepartment().getDepartmentName());
        payroll.setDesignationName(employee.getDesignation() == null ? null
                : employee.getDesignation().getDesignationName());
        payroll.setEmploymentStatus(employee.getStatus());
        payroll.setGrossSalary(employee.getGrossSalary());
        payroll.setGrossSalaryWage(employee.getGrossSalaryWage());
        payroll.setPfBasic(employee.getPfBasic());
    }

    private void snapshotRule(Payroll payroll, SalaryRule rule) {
        payroll.setRuleBasicDaPercent(rule.getBasicDaPercent());
        payroll.setRulePfPercent(rule.getPfPercent());
        payroll.setRuleEsicPercent(rule.getEsicPercent());
    }
}
