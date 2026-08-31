package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.dto.ReportFilter;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.SalaryRevision;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.SalaryRevisionRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.util.AmountInWords;
import com.accusharp.hrms.util.CsvWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The payroll-side registers: the payroll register itself, the payslip
 * register, salary revisions with their arrears exposure, the bank
 * disbursement advice, and the full &amp; final worksheet.
 *
 * <p>Like every other report in this package these only aggregate what
 * payroll already recorded. The one figure that is computed rather than read
 * is the arrears estimate, and even that reuses
 * {@link SalaryCalculationService#deriveStructure} and the same
 * apportion-payable-days-by-calendar-days rule
 * {@code PayrollService.setSegmentedGrossEarnings} uses, rather than
 * inventing a second way to value a mid-period raise.
 */
@Service
@RequiredArgsConstructor
public class PayrollRegisterService {

    private static final int SCALE = 2;
    private static final int DAY_SCALE = 1;

    private final PayrollService payrollService;
    private final PayrollRepository payrollRepository;
    private final SalaryRevisionRepository salaryRevisionRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final SalaryRuleService salaryRuleService;
    private final SalaryCalculationService salaryCalculationService;
    private final StatutoryReportService statutoryReportService;
    private final ReportScope reportScope;

    // ---- payroll register ---------------------------------------------------

    /** Full CTC breakup and every earning/deduction head, one row per employee for the run. */
    @Transactional(readOnly = true)
    public List<ReportDtos.PayrollRegisterRow> payrollRegister(int month, int year, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);
        String period = PayrollAuditService.periodLabel(YearMonth.of(year, month));

        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> byUserId.containsKey(payroll.getEmployeeId()))
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> {
                    Employee employee = byUserId.get(payroll.getEmployeeId());
                    return new ReportDtos.PayrollRegisterRow(
                            payroll.getEmployeeId(),
                            payroll.getEmployeeCode(),
                            payroll.getEmployeeName(),
                            payroll.getDepartmentName() != null ? payroll.getDepartmentName()
                                    : names.department(employee),
                            payroll.getDesignationName() != null ? payroll.getDesignationName()
                                    : names.designation(employee),
                            names.category(employee),
                            payroll.getEmploymentStatus(),
                            employee == null ? null : employee.getJoiningDate(),
                            employee == null ? null : employee.getUanNo(),
                            employee == null ? null : employee.getEsicIpNo(),
                            month, year, period, payroll.getRevision(),
                            payroll.getGrossSalary(), payroll.getGrossSalaryWage(), payroll.getPfBasic(),
                            payroll.getPayableDays(), payroll.getLopDays(),
                            payroll.getEarnBasicDA(), payroll.getEarnHra(), payroll.getEarnConveyance(),
                            payroll.getEarnEducation(), payroll.getEarnMedical(), payroll.getEarnOther(),
                            payroll.getOtAllowance(), payroll.getBonus(), payroll.getIncentive(),
                            payroll.getTotalEarnings(),
                            payroll.getPfDeduction(), payroll.getEsic(), payroll.getProfessionalTax(),
                            payroll.getMlwf(), payroll.getTds(), payroll.getAdvanceDeduction(),
                            payroll.getLoanDeduction(), payroll.getCanteen(), payroll.getTotalDeduction(),
                            payroll.getNetSalary());
                })
                .toList();
    }

    /** Spreadsheet export of {@link #payrollRegister}. */
    @Transactional(readOnly = true)
    public String payrollRegisterCsv(int month, int year, ReportFilter filter) {
        CsvWriter csv = new CsvWriter().header(
                "Employee Code", "User ID", "Employee Name", "Department", "Designation", "Category",
                "Employment Type", "Date of Joining", "UAN", "ESIC IP", "Period", "Revision",
                "Gross Salary", "Gross Wage", "PF Basic", "Payable Days", "LOP Days",
                "Basic+DA", "HRA", "Conveyance", "Education", "Medical", "Other", "Overtime",
                "Bonus", "Incentive", "Total Earnings",
                "PF", "ESIC", "Professional Tax", "MLWF", "TDS", "Advance", "Loan", "Canteen",
                "Total Deductions", "Net Salary");

        for (ReportDtos.PayrollRegisterRow row : payrollRegister(month, year, filter)) {
            csv.row(row.employeeCode(), row.userId(), row.employeeName(), row.departmentName(),
                    row.designationName(), row.categoryName(), row.employmentStatus(), row.joiningDate(),
                    row.uanNo(), row.esicIpNo(), row.period(), row.revision(),
                    row.grossSalary(), row.grossSalaryWage(), row.pfBasic(), row.payableDays(), row.lopDays(),
                    row.earnBasicDA(), row.earnHra(), row.earnConveyance(), row.earnEducation(),
                    row.earnMedical(), row.earnOther(), row.otAllowance(), row.bonus(), row.incentive(),
                    row.totalEarnings(),
                    row.pfDeduction(), row.esic(), row.professionalTax(), row.mlwf(), row.tds(),
                    row.advanceDeduction(), row.loanDeduction(), row.canteen(), row.totalDeductions(),
                    row.netSalary());
        }
        return csv.build();
    }

    // ---- payslip register ---------------------------------------------------

    /**
     * Every employee's slip totals for the period in one list.
     *
     * <p>Distinct from {@code SalarySlipService.getSlipsForPeriod}, which is
     * self-service scoped for the individual-slip endpoints: this is a
     * report, so it stays company-wide for SUPERVISOR/HR/ADMIN like the rest
     * of {@code /api/reports} - see {@code PayrollService.getPeriodForCaller}
     * for why the two scopes differ.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.PayslipRegisterRow> payslipRegister(int month, int year, ReportFilter filter) {
        Map<String, Employee> byUserId = reportScope.byUserId(reportScope.employees(filter));
        String period = PayrollAuditService.periodLabel(YearMonth.of(year, month));

        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> byUserId.containsKey(payroll.getEmployeeId()))
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> new ReportDtos.PayslipRegisterRow(
                        payroll.getEmployeeId(),
                        payroll.getEmployeeCode(),
                        payroll.getEmployeeName(),
                        payroll.getDepartmentName(),
                        payroll.getDesignationName(),
                        period,
                        payroll.getRevision(),
                        payroll.getGeneratedAt(),
                        payroll.getPayableDays(),
                        payroll.getLopDays(),
                        payroll.getTotalEarnings(),
                        payroll.getTotalDeduction(),
                        payroll.getNetSalary(),
                        AmountInWords.convert(payroll.getNetSalary())))
                .toList();
    }

    // ---- salary revisions & arrears ----------------------------------------

    /**
     * Every salary revision taking effect in the window, with the periods it
     * left underpaid and what settling them would cost.
     *
     * <p>Arrears only arise from a <em>retrospective</em> revision - one
     * entered after payroll for an affected period was already generated.
     * Payroll generated after the revision already splits the period at the
     * effective date on its own, so those periods carry no arrears and are
     * correctly absent here.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.SalaryRevisionRow> salaryRevisionReport(LocalDate from, LocalDate to,
                                                                   ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);
        if (byUserId.isEmpty()) {
            return List.of();
        }

        Map<String, List<Payroll>> payrollsByEmployee = payrollRepository
                .findAllByEmployeeIdInAndStatusOrderByYearDescMonthDesc(byUserId.keySet(), PayrollStatus.GENERATED)
                .stream()
                .collect(Collectors.groupingBy(Payroll::getEmployeeId));

        return salaryRevisionRepository
                .findAllByEmployeeIdInAndEffectiveDateBetweenOrderByEffectiveDateDesc(byUserId.keySet(), from, to)
                .stream()
                .map(revision -> {
                    Employee employee = byUserId.get(revision.getEmployeeId());
                    List<Payroll> affected = payrollsPaidAtOldRate(revision,
                            payrollsByEmployee.getOrDefault(revision.getEmployeeId(), List.of()));

                    return new ReportDtos.SalaryRevisionRow(
                            revision.getId(),
                            revision.getEmployeeId(),
                            employee == null ? null : employee.getEmployeeCode(),
                            employee == null ? null : employee.getEmployeeName(),
                            names.department(employee),
                            names.designation(employee),
                            revision.getEffectiveDate(),
                            revision.getPreviousGrossSalary(),
                            revision.getNewGrossSalary(),
                            revision.getNewGrossSalary().subtract(revision.getPreviousGrossSalary())
                                    .setScale(SCALE, RoundingMode.HALF_UP),
                            revision.getHikePercent(),
                            revision.getReason(),
                            revision.getRemarks(),
                            revision.getRevisedBy(),
                            revision.getCreatedAt(),
                            affected.stream().map(this::periodOf).toList(),
                            estimatedArrears(revision, employee, affected));
                })
                .toList();
    }

    /**
     * Periods already generated at the superseded gross that end on or after
     * the effective date. Comparing against the payroll's own snapshotted
     * {@code grossSalary} is what distinguishes "paid before the raise was
     * entered" from "paid after, and already segmented correctly".
     */
    private List<Payroll> payrollsPaidAtOldRate(SalaryRevision revision, List<Payroll> payrolls) {
        return payrolls.stream()
                .filter(payroll -> payroll.getGrossSalary() != null
                        && payroll.getGrossSalary().compareTo(revision.getPreviousGrossSalary()) == 0)
                .filter(payroll -> !periodOfPayroll(payroll).atEndOfMonth().isBefore(revision.getEffectiveDate()))
                .sorted(Comparator.comparing(Payroll::getYear).thenComparing(Payroll::getMonth))
                .toList();
    }

    /**
     * What regenerating the affected periods would add.
     *
     * <p>Values the raise the way payroll itself would: re-derive the
     * gross-derived structure at both the old and the new gross under the
     * employee's own company rule, take the difference, then prorate it over
     * the payable days that fall on or after the effective date - the same
     * calendar-day apportionment {@code PayrollService.setSegmentedGrossEarnings}
     * applies to a mid-period revision. Medical and other allowances are flat
     * amounts a revision never touches, so they contribute nothing, exactly as
     * in payroll.
     */
    private BigDecimal estimatedArrears(SalaryRevision revision, Employee employee, List<Payroll> affected) {
        if (employee == null || affected.isEmpty()) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        SalaryRule rule = salaryRuleService.getActiveRuleForCompany(employee.getCompany());
        BigDecimal wageDelta = wageOf(salaryCalculationService.deriveStructure(revision.getNewGrossSalary(), rule))
                .subtract(wageOf(salaryCalculationService.deriveStructure(revision.getPreviousGrossSalary(), rule)));

        BigDecimal arrears = BigDecimal.ZERO;
        for (Payroll payroll : affected) {
            YearMonth period = periodOfPayroll(payroll);
            BigDecimal prorationBase = prorationBase(payroll, period);
            if (prorationBase.signum() <= 0 || payroll.getDaysInMonth() == null || payroll.getDaysInMonth() <= 0) {
                continue;
            }

            LocalDate effectiveFrom = revision.getEffectiveDate().isAfter(period.atDay(1))
                    ? revision.getEffectiveDate() : period.atDay(1);
            long effectiveDays = ChronoUnit.DAYS.between(effectiveFrom, period.atEndOfMonth()) + 1;

            BigDecimal payableDaysInSegment = nullSafe(payroll.getPayableDays())
                    .multiply(BigDecimal.valueOf(effectiveDays))
                    .divide(BigDecimal.valueOf(payroll.getDaysInMonth()), DAY_SCALE, RoundingMode.HALF_UP);

            arrears = arrears.add(salaryCalculationService.prorate(wageDelta, prorationBase, payableDaysInSegment));
        }
        return arrears.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal wageOf(SalaryCalculationService.DerivedStructure structure) {
        return structure.basicDA()
                .add(structure.hra())
                .add(structure.conveyanceAllowance())
                .add(structure.educationAllowance());
    }

    // ---- bank disbursement advice ------------------------------------------

    /**
     * The credit instruction for a period: one line per employee with a net
     * salary to pay, plus the control totals a bank file is reconciled
     * against.
     *
     * <p>Employees whose bank details are missing are still listed - with a
     * remark instead of an account number - because an advice that silently
     * dropped them would under-report the payout and hide the data gap that
     * caused it. {@code missingBankDetailsCount} is what to act on.
     */
    @Transactional(readOnly = true)
    public ReportDtos.BankTransferAdvice bankTransferAdvice(int month, int year, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);
        String period = PayrollAuditService.periodLabel(YearMonth.of(year, month));

        List<ReportDtos.BankTransferRow> rows = payrollService.getPeriod(month, year).stream()
                .filter(payroll -> byUserId.containsKey(payroll.getEmployeeId()))
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> {
                    Employee employee = byUserId.get(payroll.getEmployeeId());
                    String account = employee == null ? null : employee.getBankAccountNo();
                    String ifsc = employee == null ? null : employee.getBankIfscNo();
                    return new ReportDtos.BankTransferRow(
                            payroll.getEmployeeId(),
                            payroll.getEmployeeCode(),
                            payroll.getEmployeeName(),
                            payroll.getDepartmentName() != null ? payroll.getDepartmentName()
                                    : names.department(employee),
                            account,
                            ifsc,
                            period,
                            payroll.getNetSalary(),
                            bankRemark(account, ifsc, payroll.getNetSalary()));
                })
                .toList();

        return new ReportDtos.BankTransferAdvice(
                period,
                rows.size(),
                rows.stream().filter(row -> signum(row.netSalary()) > 0).count(),
                rows.stream().filter(row -> isBlank(row.bankAccountNo()) || isBlank(row.bankIfscNo())).count(),
                rows.stream().map(ReportDtos.BankTransferRow::netSalary)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(SCALE, RoundingMode.HALF_UP),
                rows);
    }

    /** Spreadsheet export of {@link #bankTransferAdvice}, control totals included. */
    @Transactional(readOnly = true)
    public String bankTransferCsv(int month, int year, ReportFilter filter) {
        ReportDtos.BankTransferAdvice advice = bankTransferAdvice(month, year, filter);
        CsvWriter csv = new CsvWriter().header(
                "Employee Code", "User ID", "Employee Name", "Department",
                "Bank Account No", "IFSC", "Period", "Net Salary", "Remarks");

        for (ReportDtos.BankTransferRow row : advice.rows()) {
            csv.row(row.employeeCode(), row.userId(), row.employeeName(), row.departmentName(),
                    row.bankAccountNo(), row.bankIfscNo(), row.period(), row.netSalary(), row.remarks());
        }
        return csv.blankLine()
                .row("Employees", advice.employeeCount())
                .row("With a payable amount", advice.payableCount())
                .row("Missing bank details", advice.missingBankDetailsCount())
                .row("Total amount", advice.totalAmount())
                .build();
    }

    private String bankRemark(String account, String ifsc, BigDecimal netSalary) {
        if (signum(netSalary) <= 0) {
            return "Nothing payable this period";
        }
        if (isBlank(account) && isBlank(ifsc)) {
            return "Bank account and IFSC missing";
        }
        if (isBlank(account)) {
            return "Bank account missing";
        }
        if (isBlank(ifsc)) {
            return "IFSC missing";
        }
        return null;
    }

    // ---- full & final -------------------------------------------------------

    /**
     * The full &amp; final worksheet for everyone relieved inside the window.
     *
     * <p>Assembles what already exists - the last generated payroll, the
     * paid-leave balance, the employee master and the gratuity accrual - and
     * flags whether the final month has actually been run yet. It deliberately
     * settles nothing: leave encashment rates and notice-period recovery have
     * no policy anywhere in this system, so inventing one here would put a
     * number in front of HR that no rule backs.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.FullAndFinalRow> fullAndFinalReport(LocalDate from, LocalDate to,
                                                                ReportFilter filter) {
        List<Employee> leavers = reportScope.employees(filter).stream()
                .filter(employee -> employee.getRelievingDate() != null)
                .filter(employee -> !employee.getRelievingDate().isBefore(from)
                        && !employee.getRelievingDate().isAfter(to))
                .toList();
        if (leavers.isEmpty()) {
            return List.of();
        }

        ReportScope.MasterNames names = reportScope.names(leavers);
        List<String> userIds = leavers.stream().map(Employee::getUserId).toList();

        Map<String, List<Payroll>> payrollsByEmployee = payrollRepository
                .findAllByEmployeeIdInAndStatusOrderByYearDescMonthDesc(userIds, PayrollStatus.GENERATED)
                .stream()
                .collect(Collectors.groupingBy(Payroll::getEmployeeId));

        Map<String, BigDecimal> paidLeaveBalances = paidLeaveBalancesFor(leavers);

        return leavers.stream()
                .sorted(Comparator.comparing(Employee::getRelievingDate).reversed())
                .map(employee -> {
                    List<Payroll> runs = payrollsByEmployee.getOrDefault(employee.getUserId(), List.of());
                    Payroll last = runs.stream()
                            .max(Comparator.comparing(Payroll::getYear).thenComparing(Payroll::getMonth))
                            .orElse(null);
                    YearMonth finalPeriod = YearMonth.from(employee.getRelievingDate());
                    boolean finalRun = runs.stream().anyMatch(payroll ->
                            periodOfPayroll(payroll).equals(finalPeriod));

                    BigDecimal years = StatutoryReportService.yearsOfService(
                            employee.getJoiningDate(), employee.getRelievingDate());
                    int completedYears = years.setScale(0, RoundingMode.DOWN).intValue();
                    boolean gratuityEligible =
                            completedYears >= StatutoryReportService.GRATUITY_ELIGIBLE_YEARS;

                    return new ReportDtos.FullAndFinalRow(
                            employee.getUserId(),
                            employee.getEmployeeCode(),
                            employee.getEmployeeName(),
                            names.department(employee),
                            names.designation(employee),
                            employee.getJoiningDate(),
                            employee.getRelievingDate(),
                            years,
                            last == null ? null : periodOf(last),
                            last == null ? null : last.getNetSalary(),
                            finalRun,
                            employee.getGrossSalary(),
                            employee.getBasicDA(),
                            paidLeaveBalances.getOrDefault(employee.getUserId(),
                                    BigDecimal.ZERO.setScale(DAY_SCALE)),
                            last == null ? null : last.getAdvanceDeduction(),
                            last == null ? null : last.getLoanDeduction(),
                            gratuityEligible,
                            statutoryReportService.gratuityAccrual(employee.getBasicDA(), completedYears));
                })
                .toList();
    }

    /**
     * Unused balance of the <em>paid</em> leave types in the year each
     * employee was relieved in - unpaid leave has no balance worth settling.
     * One query per distinct relieving year rather than one per leaver, and
     * narrowed to these employees so a whole company's balances don't get
     * pulled into the map to serve a handful of rows.
     */
    private Map<String, BigDecimal> paidLeaveBalancesFor(List<Employee> leavers) {
        Map<String, Employee> byUserId = reportScope.byUserId(leavers);
        Map<String, BigDecimal> balances = new HashMap<>();
        leavers.stream()
                .map(employee -> employee.getRelievingDate().getYear())
                .distinct()
                .forEach(year -> leaveBalanceRepository.findAllByLeaveYear(year).stream()
                        .filter(balance -> byUserId.containsKey(balance.getUserId()))
                        .filter(balance -> balance.getLeaveType().isPaid())
                        .forEach(balance -> balances.merge(balance.getUserId(), available(balance),
                                BigDecimal::add)));
        return balances;
    }

    private BigDecimal available(LeaveBalance balance) {
        return balance.available().setScale(DAY_SCALE, RoundingMode.HALF_UP);
    }

    // ---- helpers ------------------------------------------------------------

    private BigDecimal prorationBase(Payroll payroll, YearMonth period) {
        boolean dayWise = payroll.getEmploymentStatus() != null
                && payroll.getEmploymentStatus().isPaidPerAttendedDay();
        if (dayWise && payroll.getRuleDayWiseDaysInMonth() != null) {
            return BigDecimal.valueOf(payroll.getRuleDayWiseDaysInMonth()).setScale(DAY_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(period.lengthOfMonth()).setScale(DAY_SCALE, RoundingMode.HALF_UP);
    }

    private YearMonth periodOfPayroll(Payroll payroll) {
        return YearMonth.of(payroll.getYear(), payroll.getMonth());
    }

    private String periodOf(Payroll payroll) {
        return PayrollAuditService.periodLabel(periodOfPayroll(payroll));
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int signum(BigDecimal value) {
        return value == null ? 0 : value.signum();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
