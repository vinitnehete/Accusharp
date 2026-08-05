package com.accusharp.hrms.service.payroll;

import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.PayrollRepository;
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
import java.util.List;

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
 *   <li><b>everyone else</b> - paid against the month's working days, reduced
 *       only by loss of pay.</li>
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

    /** Generates the period once; a second call is a conflict. */
    @Transactional
    public Payroll generate(PayrollRequest request) {
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
     */
    @Transactional
    public Payroll regenerate(PayrollRequest request) {
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

    @Transactional(readOnly = true)
    public Payroll getById(Long id) {
        return payrollRepository.findById(id).orElseThrow(() -> NotFoundException.of("Payroll", id));
    }

    @Transactional(readOnly = true)
    public Payroll getCurrent(String employeeId, int month, int year) {
        return payrollRepository.findByEmployeeIdAndMonthAndYearAndStatus(
                        employeeId, month, year, PayrollStatus.GENERATED)
                .orElseThrow(() -> NotFoundException.of("Payroll", employeeId + " " + month + "/" + year));
    }

    @Transactional(readOnly = true)
    public List<Payroll> getRevisions(String employeeId, int month, int year) {
        return payrollRepository.findAllByEmployeeIdAndMonthAndYearOrderByRevisionDesc(employeeId, month, year);
    }

    @Transactional(readOnly = true)
    public List<Payroll> getHistory(String employeeId) {
        return payrollRepository.findAllByEmployeeIdOrderByYearDescMonthDesc(employeeId);
    }

    @Transactional(readOnly = true)
    public List<Payroll> getPeriod(int month, int year) {
        return payrollRepository.findAllByMonthAndYearAndStatus(month, year, PayrollStatus.GENERATED);
    }

    // ---- the calculation ---------------------------------------------------

    private Payroll build(PayrollRequest request, int revision) {
        Employee employee = employeeService.getEntityByUserId(request.getEmployeeId());
        SalaryRule rule = salaryRuleService.getActiveRule();
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
        BigDecimal totalDays = dayWise
                ? BigDecimal.valueOf(rule.getDayWiseDaysInMonth())
                : BigDecimal.valueOf(Math.max(attendance.getWorkingDays(), 1));

        BigDecimal presentDays = attendance.getPresentDays();
        BigDecimal paidLeaveDays = paidLeaveDays(attendance);

        BigDecimal lopDays;
        BigDecimal payableDays;
        if (dayWise) {
            // Attendance is the pay: no LOP concept, you are paid what you worked.
            lopDays = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
            payableDays = presentDays.add(paidLeaveDays).min(totalDays);
        } else {
            lopDays = lopCalculationService.calculateLopDays(
                    BigDecimal.valueOf(attendance.getWorkingDays()), presentDays, paidLeaveDays);
            payableDays = lopCalculationService.calculatePayableDays(totalDays, lopDays);
        }

        payroll.setDaysInMonth(period.lengthOfMonth());
        payroll.setWorkingDays((int) attendance.getWorkingDays());
        payroll.setPresentDays(presentDays);
        payroll.setPaidLeaveDays(paidLeaveDays);
        payroll.setLopDays(lopDays);
        payroll.setPayableDays(payableDays);
        payroll.setTotalHours(attendance.getTotalHours());
        payroll.setOvertimeHours(attendance.getOvertimeHours());

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
        payroll.setOtAllowance(overtimeAllowance(employee, attendance, perHour, rule));

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
        payroll.setTds(salaryCalculationService.scaled(request.getTds()));
        payroll.setAdvanceDeduction(salaryCalculationService.scaled(request.getAdvanceDeduction()));
        payroll.setLoanDeduction(salaryCalculationService.scaled(request.getLoanDeduction()));
        payroll.setCanteen(salaryCalculationService.scaled(request.getCanteen()));

        // Reported for transparency only - the earnings above were already
        // prorated down by the same days, so adding it would deduct twice.
        payroll.setLopDeduction(deductionCalculationService.calculateLopDeduction(
                employee.getGrossSalaryWage(), totalDays, lopDays));

        BigDecimal totalDeduction = deductionCalculationService.sum(
                payroll.getPfDeduction(), payroll.getEsic(), payroll.getProfessionalTax(), payroll.getTds(),
                payroll.getAdvanceDeduction(), payroll.getLoanDeduction(), payroll.getCanteen());
        payroll.setTotalDeduction(totalDeduction);

        payroll.setNetSalary(totalEarnings.subtract(totalDeduction).setScale(SCALE, RoundingMode.HALF_UP));

        payroll.setGeneratedAt(Instant.now());
        payroll.setGeneratedBy(request.getGeneratedBy());

        log.info("payroll.generate employeeId={} period={}/{} revision={} payableDays={} net={}",
                payroll.getEmployeeId(), payroll.getMonth(), payroll.getYear(), revision,
                payableDays, payroll.getNetSalary());

        Payroll saved = payrollRepository.save(payroll);

        // Freeze the attendance this payroll was computed from, so the slip
        // stays reproducible. Correcting it later means unlock, fix, regenerate.
        attendanceService.lockMonth(employee.getUserId(), period);

        return saved;
    }

    /**
     * Overtime is paid only to eligible employees, on hours the attendance
     * engine already measured against each day's own shift length.
     */
    private BigDecimal overtimeAllowance(Employee employee, MonthlyAttendanceSummary attendance,
                                         BigDecimal perHour, SalaryRule rule) {
        if (!employee.isOvertimeEligible() || attendance.getOvertimeHours() == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return attendance.getOvertimeHours()
                .multiply(perHour)
                .multiply(rule.getOvertimeRateMultiplier())
                .setScale(SCALE, RoundingMode.HALF_UP);
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
