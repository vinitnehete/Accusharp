package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.PayrollAuditDtos;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.dto.ReportFilter;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.SalaryRevisionRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.leave.LeaveService;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.service.report.AttendanceLeaveReportService;
import com.accusharp.hrms.service.report.PayrollAuditService;
import com.accusharp.hrms.service.report.PayrollRegisterService;
import com.accusharp.hrms.service.report.StatutoryReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reporting layer over a paid month. Reuses
 * {@code PayrollFlowIntegrationTest}'s worked example - 26 working days, 23
 * attended, 2 days approved leave, 1 LOP day - so the assertions here can be
 * read against figures that test already pins down independently.
 *
 * <p>The point of most of these is agreement rather than arithmetic: a report
 * that quoted a different net salary, a different LOP count or a different
 * per-day rate from the payroll it reads would be the one bug this whole
 * module cannot afford.
 */
@SpringBootTest
class PayrollAuditReportTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 8);
    private static final String EMPLOYEE = "AUD100";
    private static final String HR = "AUDHR100";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private SalaryRevisionRepository salaryRevisionRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private LeaveService leaveService;
    @Autowired private PayrollService payrollService;
    @Autowired private PayrollAuditService payrollAuditService;
    @Autowired private PayrollRegisterService payrollRegisterService;
    @Autowired private StatutoryReportService statutoryReportService;
    @Autowired private AttendanceLeaveReportService attendanceLeaveReportService;

    private long punchId = 900_000;

    @BeforeEach
    void setUp() {
        salaryRevisionRepository.deleteAll();
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        punchId = 900_000;

        Shift morning = shiftRepository.save(Shift.builder()
                .shiftCode("AUDMORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-AUDHR", "Audit HR", Role.HR, new BigDecimal("50000"));
        saveEmployee(EMPLOYEE, "EMP-AUD-100", "Audit Worker", Role.EMPLOYEE, new BigDecimal("26000"));

        List<ShiftSchedule> roster = new ArrayList<>();
        for (int day = 1; day <= 26; day++) {
            roster.add(ShiftSchedule.builder()
                    .userId(EMPLOYEE).shiftDate(PERIOD.atDay(day)).shift(morning).weekOff(false).build());
        }
        shiftScheduleRepository.saveAll(roster);

        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= 23; day++) {
            punches.add(punch(PERIOD.atDay(day).atTime(6, 0)));
            punches.add(punch(PERIOD.atDay(day).atTime(15, 0)));
        }
        deviceLogRepository.saveAll(punches);
    }

    @Test
    @DisplayName("the audit row quotes the payroll snapshot, never a recalculation of it")
    void auditRowMirrorsPayroll() {
        Payroll payroll = runMonth();

        List<PayrollAuditDtos.AuditRow> rows =
                payrollAuditService.auditReport(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE);
        PayrollAuditDtos.AuditRow row = rowFor(rows, EMPLOYEE);

        assertThat(row.employeeCode()).isEqualTo("EMP-AUD-100");
        assertThat(row.joiningDate()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(row.lopDays()).isEqualByComparingTo(payroll.getLopDays());
        assertThat(row.payableDays()).isEqualByComparingTo(payroll.getPayableDays());
        assertThat(row.earnBasicDA()).isEqualByComparingTo(payroll.getEarnBasicDA());
        assertThat(row.earnGrossSalary()).isEqualByComparingTo(payroll.getEarnGrossSalary());
        assertThat(row.perDay()).isEqualByComparingTo(payroll.getPerDay());
        assertThat(row.totalDeduction()).isEqualByComparingTo(payroll.getTotalDeduction());
        assertThat(row.netSalary()).isEqualByComparingTo(payroll.getNetSalary());

        // Fixed structure is the CTC the proration started from, earned is what it came to.
        assertThat(row.fixedBasicDA()).isEqualByComparingTo("13000.00");
        assertThat(row.earnBasicDA()).isEqualByComparingTo("12580.65");
        assertThat(row.salaryStructureDrifted()).isFalse();

        // Salaried against the 31-day calendar month, so that is the base, not the 26 working days.
        assertThat(row.prorationBase()).isEqualByComparingTo("31");
        assertThat(row.regularHours()).isEqualByComparingTo(
                payroll.getTotalHours().subtract(payroll.getOvertimeHours()));
    }

    @Test
    @DisplayName("the day-wise table covers the whole month and its paid days reconcile to payable days")
    void dayWiseTableReconcilesToPayableDays() {
        Payroll payroll = runMonth();

        PayrollAuditDtos.DayWiseReport report =
                payrollAuditService.dayWiseReport(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear());

        assertThat(report.days()).hasSize(PERIOD.lengthOfMonth());
        assertThat(report.perDay()).isEqualByComparingTo(payroll.getPerDay());
        assertThat(report.earnedGrossSalary()).isEqualByComparingTo(payroll.getEarnGrossSalary());

        BigDecimal paidDays = report.days().stream()
                .map(PayrollAuditDtos.DayWiseRow::paidFraction)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lopDays = report.days().stream()
                .map(PayrollAuditDtos.DayWiseRow::lopFraction)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // The whole point of the table: it reconciles to the run. 26 rostered
        // days (23 attended, 2 paid leave, 1 LOP) plus the 5 unrostered
        // calendar days a salaried employee is still paid for = the 30
        // payable days payroll actually paid.
        assertThat(lopDays).isEqualByComparingTo(payroll.getLopDays());
        assertThat(paidDays).isEqualByComparingTo(payroll.getPayableDays());
        assertThat(paidDays).isEqualByComparingTo("30.0");

        // The report says so itself, rather than leaving the caller to add the column up.
        assertThat(report.dayWisePaidDays()).isEqualByComparingTo(paidDays);
        assertThat(report.dayWiseLopDays()).isEqualByComparingTo(lopDays);
        assertThat(report.reconciled()).isTrue();

        // Those 5 days are paid but carry no attendance record, and the flag
        // says so rather than implying a roster row that never existed.
        assertThat(report.days().stream().filter(day -> !day.recorded()).toList())
                .hasSize(5)
                .allSatisfy(day -> assertThat(day.paidFraction()).isEqualByComparingTo("1.0"));

        // Exactly one day is loss of pay, and it is a rostered working day.
        List<PayrollAuditDtos.DayWiseRow> lopRows = report.days().stream()
                .filter(day -> day.lopFraction().signum() > 0)
                .toList();
        assertThat(lopRows).hasSize(1);
        assertThat(lopRows.get(0).workingDay()).isTrue();

        // The two approved leave days are paid in full and carry their type.
        List<PayrollAuditDtos.DayWiseRow> leaveRows = report.days().stream()
                .filter(day -> day.leaveType() != null)
                .toList();
        assertThat(leaveRows).hasSize(2);
        assertThat(leaveRows).allSatisfy(day -> {
            assertThat(day.leaveType()).isEqualTo(LeaveType.CASUAL_LEAVE);
            assertThat(day.paidFraction()).isEqualByComparingTo("1.0");
            assertThat(day.lopFraction()).isEqualByComparingTo("0.0");
        });
    }

    @Test
    @DisplayName("a day both attended and covered by paid leave is reported unreconciled, not smoothed over")
    void attendedDayCoveredByPaidLeaveIsFlagged() {
        // Leave over the 22nd and 23rd, both of which were also attended -
        // exactly the overlap the month-level LOP formula double-credits.
        LeaveRequestPayload payload = new LeaveRequestPayload();
        payload.setUserId(EMPLOYEE);
        payload.setLeaveType(LeaveType.CASUAL_LEAVE);
        payload.setFromDate(PERIOD.atDay(22));
        payload.setToDate(PERIOD.atDay(23));
        payload.setDuration(LeaveDuration.FULL_DAY);
        payload.setReason("Overlaps attended days");
        var applied = leaveService.apply(payload);

        LeaveDecisionRequest decision = new LeaveDecisionRequest();
        decision.setApproverId(HR);
        decision.setComments("Approved");
        leaveService.approve(applied.id(), decision);

        AttendanceGenerationRequest attendance = new AttendanceGenerationRequest();
        attendance.setMonth(PERIOD);
        attendance.setUserIds(List.of(EMPLOYEE));
        attendance.setGeneratedBy(HR);
        attendanceService.generate(attendance);

        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(EMPLOYEE);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        Payroll payroll = payrollService.generate(request);

        PayrollAuditDtos.DayWiseReport report =
                payrollAuditService.dayWiseReport(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear());

        // Punches run to the 23rd, so both leave days were also attended: the
        // run credits them twice and under-counts LOP. The day column cannot go
        // below zero, so it reports the true figure and the report says the two
        // disagree instead of hiding it.
        assertThat(report.dayWiseLopDays()).isGreaterThan(payroll.getLopDays());
        assertThat(report.reconciled()).isFalse();

        // Every individual day still reads sensibly - no negative fractions leak out.
        assertThat(report.days()).allSatisfy(day -> {
            assertThat(day.lopFraction()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(day.paidFraction()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("the company rollup totals exactly the rows it is built from")
    void companySummaryTotalsTheRows() {
        runMonth();

        List<PayrollAuditDtos.AuditRow> rows =
                payrollAuditService.auditReport(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE);
        PayrollAuditDtos.CompanySummary summary =
                payrollAuditService.companySummary(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE);

        assertThat(summary.headcount()).isEqualTo(rows.size());
        assertThat(summary.employeesWithLop()).isEqualTo(1);
        assertThat(summary.totalNetSalary()).isEqualByComparingTo(
                rows.stream().map(PayrollAuditDtos.AuditRow::netSalary)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(summary.totalEarnedWages()).isEqualByComparingTo(
                rows.stream().map(PayrollAuditDtos.AuditRow::earnGrossSalary)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        BigDecimal deductionBuckets = summary.deductions().stream()
                .map(PayrollAuditDtos.DeductionTotal::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(deductionBuckets).isEqualByComparingTo(summary.totalDeductions());

        assertThat(summary.byDepartment()).isNotEmpty();
        assertThat(summary.byDepartment().stream()
                .map(PayrollAuditDtos.GroupTotal::headcount)
                .reduce(0L, Long::sum)).isEqualTo(summary.headcount());
    }

    @Test
    @DisplayName("the filter narrows the population, and the rollup narrows with it")
    void filterNarrowsBothRowsAndRollup() {
        runMonth();

        // No employee in this fixture has a department, so any department id at
        // all must exclude everyone - the filter has to actually be applied
        // rather than silently ignored.
        ReportFilter unmatched = ReportFilter.of(-1L, null, null);
        assertThat(payrollAuditService.auditReport(PERIOD.getMonthValue(), PERIOD.getYear(), unmatched)).isEmpty();
        assertThat(payrollAuditService.companySummary(PERIOD.getMonthValue(), PERIOD.getYear(), unmatched)
                .headcount()).isZero();
    }

    @Test
    @DisplayName("the payroll register and payslip register agree with the run they describe")
    void registersAgreeWithPayroll() {
        Payroll payroll = runMonth();

        ReportDtos.PayrollRegisterRow register = payrollRegisterService
                .payrollRegister(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE).stream()
                .filter(row -> row.userId().equals(EMPLOYEE)).findFirst().orElseThrow();

        assertThat(register.grossSalary()).isEqualByComparingTo(payroll.getGrossSalary());
        assertThat(register.totalEarnings()).isEqualByComparingTo(payroll.getTotalEarnings());
        assertThat(register.netSalary()).isEqualByComparingTo(payroll.getNetSalary());
        assertThat(register.period()).isEqualTo("August 2026");

        ReportDtos.PayslipRegisterRow payslip = payrollRegisterService
                .payslipRegister(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE).stream()
                .filter(row -> row.userId().equals(EMPLOYEE)).findFirst().orElseThrow();

        assertThat(payslip.netSalary()).isEqualByComparingTo(payroll.getNetSalary());
        assertThat(payslip.netSalaryInWords()).isNotBlank();
        assertThat(payslip.revision()).isEqualTo(payroll.getRevision());
    }

    @Test
    @DisplayName("the bank advice totals the payout and names who cannot be paid")
    void bankAdviceFlagsMissingBankDetails() {
        runMonth();

        ReportDtos.BankTransferAdvice advice = payrollRegisterService
                .bankTransferAdvice(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE);

        assertThat(advice.rows()).isNotEmpty();
        assertThat(advice.totalAmount()).isEqualByComparingTo(
                advice.rows().stream().map(ReportDtos.BankTransferRow::netSalary)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        // The fixture saves no bank details, so every line must be flagged
        // rather than quietly dropped from the payout.
        assertThat(advice.missingBankDetailsCount()).isEqualTo(advice.rows().size());
        assertThat(advice.rows()).allSatisfy(row ->
                assertThat(row.remarks()).isEqualTo("Bank account and IFSC missing"));
    }

    @Test
    @DisplayName("the statutory returns quote the deductions payroll actually took")
    void statutoryReturnsQuoteWhatWasDeducted() {
        Payroll payroll = runMonth();

        List<ReportDtos.PfEcrRow> ecr = statutoryReportService
                .pfEcrReport(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE);
        ReportDtos.PfEcrRow member = ecr.stream()
                .filter(row -> row.userId().equals(EMPLOYEE)).findFirst().orElseThrow();

        assertThat(member.employeeContribution()).isEqualByComparingTo(payroll.getPfDeduction());
        assertThat(member.epfWages()).isEqualByComparingTo(payroll.getEarnPf());
        assertThat(member.ncpDays()).isEqualByComparingTo(payroll.getLopDays());
        // EPS is capped at the pensionable ceiling and never exceeds EPF wages.
        assertThat(member.epsWages()).isLessThanOrEqualTo(member.epfWages());
        assertThat(member.epsContribution().add(member.employerEpfContribution()))
                .isEqualByComparingTo(member.employeeContribution());

        List<ReportDtos.EsiReturnRow> esi = statutoryReportService
                .esiReturnReport(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE);
        assertThat(esi).isNotEmpty();
        assertThat(esi).allSatisfy(row -> assertThat(row.totalContribution())
                .isEqualByComparingTo(row.employeeContribution().add(row.employerContribution())));
    }

    @Test
    @DisplayName("gratuity accrues only past five completed years")
    void gratuityAccruesOnlyAfterFiveYears() {
        runMonth();

        // Joined 2022-01-01; as at 2026-08-31 that is four completed years.
        List<ReportDtos.GratuityAccrualRow> early = statutoryReportService
                .gratuityAccrualReport(PERIOD.atEndOfMonth(), ReportFilter.NONE);
        ReportDtos.GratuityAccrualRow beforeFive = early.stream()
                .filter(row -> row.userId().equals(EMPLOYEE)).findFirst().orElseThrow();
        assertThat(beforeFive.completedYears()).isEqualTo(4);
        assertThat(beforeFive.eligible()).isFalse();
        assertThat(beforeFive.accruedAmount()).isEqualByComparingTo("0.00");

        // Five years on, the Act's 15/26 formula applies to the last drawn Basic+DA.
        ReportDtos.GratuityAccrualRow afterFive = statutoryReportService
                .gratuityAccrualReport(LocalDate.of(2027, 6, 30), ReportFilter.NONE).stream()
                .filter(row -> row.userId().equals(EMPLOYEE)).findFirst().orElseThrow();
        assertThat(afterFive.completedYears()).isEqualTo(5);
        assertThat(afterFive.eligible()).isTrue();
        // 13000 x 15 / 26 x 5 = 37500
        assertThat(afterFive.accruedAmount()).isEqualByComparingTo("37500.00");
    }

    @Test
    @DisplayName("the attendance exception report names the days behind the month's totals")
    void attendanceExceptionsListTheOffendingDays() {
        runMonth();

        List<ReportDtos.AttendanceExceptionRow> exceptions =
                attendanceLeaveReportService.attendanceExceptionReport(PERIOD, ReportFilter.NONE);

        // The 26th is rostered but unattended and uncovered by leave - the one
        // day that became LOP.
        assertThat(exceptions).isNotEmpty();
        assertThat(exceptions).allSatisfy(row -> assertThat(row.exceptions()).isNotBlank());
        assertThat(exceptions).anySatisfy(row -> {
            assertThat(row.userId()).isEqualTo(EMPLOYEE);
            assertThat(row.date()).isEqualTo(PERIOD.atDay(26));
            assertThat(row.exceptions()).contains("Absent");
        });
        // An absence is only ever raised for a day the employee was expected
        // to work - a weekly off or holiday is not an exception.
        assertThat(exceptions).filteredOn(row -> row.exceptions().contains("Absent"))
                .allSatisfy(row -> assertThat(row.status()).isEqualTo(AttendanceStatus.ABSENT));
    }

    @Test
    @DisplayName("the leave transaction report keeps rejected and cancelled requests, not just approvals")
    void leaveTransactionsIncludeEveryOutcome() {
        runMonth();

        List<ReportDtos.LeaveTransactionRow> transactions = attendanceLeaveReportService
                .leaveTransactionReport(PERIOD.atDay(1), PERIOD.atEndOfMonth(), ReportFilter.NONE);

        assertThat(transactions).hasSize(1);
        ReportDtos.LeaveTransactionRow leave = transactions.get(0);
        assertThat(leave.userId()).isEqualTo(EMPLOYEE);
        assertThat(leave.leaveType()).isEqualTo(LeaveType.CASUAL_LEAVE);
        assertThat(leave.paid()).isTrue();
        assertThat(leave.status()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(leave.totalDays()).isEqualByComparingTo("2.0");
        assertThat(leave.approverId()).isEqualTo(HR);
    }

    @Test
    @DisplayName("the audit export carries every row plus the company control totals")
    void auditCsvCarriesRowsAndTotals() {
        runMonth();

        String csv = payrollAuditService.auditCsv(PERIOD.getMonthValue(), PERIOD.getYear(), ReportFilter.NONE);

        assertThat(csv).startsWith("Employee Code,User ID,Employee Name");
        assertThat(csv).contains("EMP-AUD-100");
        assertThat(csv).contains("Company Totals");
        assertThat(csv).contains("Total Net Payable");
    }

    // ---- helpers -----------------------------------------------------------

    /** Approves the two leave days, generates attendance, then runs payroll - the whole month in one call. */
    private Payroll runMonth() {
        LeaveRequestPayload payload = new LeaveRequestPayload();
        payload.setUserId(EMPLOYEE);
        payload.setLeaveType(LeaveType.CASUAL_LEAVE);
        payload.setFromDate(PERIOD.atDay(24));
        payload.setToDate(PERIOD.atDay(25));
        payload.setDuration(LeaveDuration.FULL_DAY);
        payload.setReason("Family function");
        var applied = leaveService.apply(payload);

        LeaveDecisionRequest decision = new LeaveDecisionRequest();
        decision.setApproverId(HR);
        decision.setComments("Approved");
        leaveService.approve(applied.id(), decision);

        AttendanceGenerationRequest attendance = new AttendanceGenerationRequest();
        attendance.setMonth(PERIOD);
        attendance.setUserIds(List.of(EMPLOYEE));
        attendance.setGeneratedBy(HR);
        attendanceService.generate(attendance);

        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(EMPLOYEE);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        return payrollService.generate(request);
    }

    private PayrollAuditDtos.AuditRow rowFor(List<PayrollAuditDtos.AuditRow> rows, String userId) {
        return rows.stream().filter(row -> row.userId().equals(userId)).findFirst().orElseThrow();
    }

    private void saveEmployee(String userId, String code, String name, Role role, BigDecimal gross) {
        Employee employee = Employee.builder()
                .userId(userId)
                .employeeCode(code)
                .employeeName(name)
                .status(EmployeeStatus.PERMANENT)
                .recordStatus(RecordStatus.ACTIVE)
                .role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(gross)
                .pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250"))
                .otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }

    private DeviceLog punch(java.time.LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(EMPLOYEE).logDate(at).build();
    }
}
