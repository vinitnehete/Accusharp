package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.dto.RecoveryAmountRequest;
import com.accusharp.hrms.dto.RecoveryDtos;
import com.accusharp.hrms.dto.RecoveryRequest;
import com.accusharp.hrms.dto.RecoveryUpdateRequest;
import com.accusharp.hrms.dto.SalarySlipResponse;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.EmployeeRecovery;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.RecoveryStatus;
import com.accusharp.hrms.enums.RecoveryTransactionType;
import com.accusharp.hrms.enums.RecoveryType;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRecoveryRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import com.accusharp.hrms.repository.RecoveryTransactionRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.service.payroll.SalarySlipService;
import com.accusharp.hrms.service.recovery.RecoveryService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loan/advance/fine/other recovery ledger, end to end through payroll.
 *
 * <p>The cases here are the ones the design exists for rather than the happy
 * path alone: that regenerating a period does not recover the same instalment
 * twice, that a month which cannot afford an instalment defers it instead of
 * losing it, and that a whole-company run recovers against outstanding
 * balances without anyone typing a figure.
 */
@SpringBootTest
class PayrollRecoveryTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final String EMPLOYEE = "REC100";
    private static final String HR = "RECHR100";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private EmployeeRecoveryRepository recoveryRepository;
    @Autowired private RecoveryTransactionRepository transactionRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private PayrollService payrollService;
    @Autowired private SalarySlipService salarySlipService;
    @Autowired private RecoveryService recoveryService;

    private long punchId = 900_000;

    @BeforeEach
    void setUp() {
        // Shared context: reset every table this flow touches, or one test's
        // loan balance leaks into the next one's payroll.
        transactionRepository.deleteAll();
        recoveryRepository.deleteAll();
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        resetNetPayFloor();

        Shift general = shiftRepository.save(Shift.builder()
                .shiftCode("REC-GEN").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-RECHR", "Recovery HR", Role.HR, new BigDecimal("60000"));
        saveEmployee(EMPLOYEE, "EMP-REC-100", "Loan Taker", Role.EMPLOYEE, new BigDecimal("30000"));

        // Fully attended every day of the period, so nothing but the
        // recoveries under test moves the net salary.
        List<ShiftSchedule> roster = new ArrayList<>();
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= PERIOD.lengthOfMonth(); day++) {
            roster.add(ShiftSchedule.builder()
                    .userId(EMPLOYEE).shiftDate(PERIOD.atDay(day)).shift(general).weekOff(false).build());
            punches.add(punch(PERIOD.atDay(day).atTime(9, 0)));
            punches.add(punch(PERIOD.atDay(day).atTime(18, 0)));
        }
        shiftScheduleRepository.saveAll(roster);
        deviceLogRepository.saveAll(punches);
        generateAttendance();
    }

    @Test
    @DisplayName("payroll recovers the scheduled loan instalment without being told an amount, "
            + "and records the balance either side of it")
    void payrollRecoversTheScheduledInstalment() {
        issueLoan(new BigDecimal("60000"), new BigDecimal("5000"));

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getLoanDeduction()).isEqualByComparingTo("5000.00");
        assertThat(payroll.getLoanOpeningBalance()).isEqualByComparingTo("60000.00");
        assertThat(payroll.getLoanClosingBalance()).isEqualByComparingTo("55000.00");
        assertThat(payroll.getRecoveryCarriedForward()).isEqualByComparingTo("0.00");

        assertThat(onlyRecovery().getOutstandingAmount()).isEqualByComparingTo("55000.00");
        assertThat(payroll.getNetSalary())
                .isEqualByComparingTo(payroll.getTotalEarnings().subtract(payroll.getTotalDeduction()));
    }

    @Test
    @DisplayName("regenerating a period gives back what the superseded revision recovered "
            + "instead of deducting the instalment a second time")
    void regeneratingDoesNotRecoverTwice() {
        issueLoan(new BigDecimal("60000"), new BigDecimal("5000"));

        payrollService.generate(payrollRequest());
        assertThat(onlyRecovery().getOutstandingAmount()).isEqualByComparingTo("55000.00");

        Payroll regenerated = payrollService.regenerate(payrollRequest());

        // The balance is where one run leaves it, not two - the reversal
        // restored 60000 before the new revision took its own 5000.
        assertThat(onlyRecovery().getOutstandingAmount()).isEqualByComparingTo("55000.00");
        assertThat(regenerated.getRevision()).isEqualTo(2);
        assertThat(regenerated.getLoanOpeningBalance()).isEqualByComparingTo("60000.00");
        assertThat(regenerated.getLoanClosingBalance()).isEqualByComparingTo("55000.00");

        // And the ledger says so out loud rather than the balance merely
        // happening to be right: repay, reverse, repay.
        List<RecoveryTransactionType> ledger = transactionRepository
                .findAllByRecoveryIdOrderByIdAsc(onlyRecovery().getId()).stream()
                .map(txn -> txn.getType()).toList();
        assertThat(ledger).containsExactly(
                RecoveryTransactionType.ISSUE,
                RecoveryTransactionType.REPAYMENT,
                RecoveryTransactionType.REVERSAL,
                RecoveryTransactionType.REPAYMENT);
    }

    @Test
    @DisplayName("scheduled recovery pays each loan's own instalment instead of draining the oldest loan")
    void eachLoanReceivesItsScheduledInstalment() {
        RecoveryDtos.RecoveryRow first = recoveryService.issue(recoveryRequest(
                RecoveryType.LOAN, new BigDecimal("60000"), new BigDecimal("5000")));
        RecoveryDtos.RecoveryRow second = recoveryService.issue(recoveryRequest(
                RecoveryType.LOAN, new BigDecimal("12000"), new BigDecimal("1000")));

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getLoanDeduction()).isEqualByComparingTo("6000");
        assertThat(recoveryRepository.findById(first.id()).orElseThrow().getOutstandingAmount())
                .isEqualByComparingTo("55000");
        assertThat(recoveryRepository.findById(second.id()).orElseThrow().getOutstandingAmount())
                .isEqualByComparingTo("11000");
    }

    @Test
    @DisplayName("a paused loan stays outstanding in statements, registers and debug balances but has no due instalment")
    void aPausedLoanStillAppearsInOutstandingBalances() {
        RecoveryDtos.RecoveryRow loan = recoveryService.issue(recoveryRequest(
                RecoveryType.LOAN, new BigDecimal("60000"), new BigDecimal("5000")));
        RecoveryUpdateRequest pause = new RecoveryUpdateRequest();
        pause.setStatus(RecoveryStatus.ON_HOLD);
        recoveryService.update(loan.id(), pause);

        assertThat(recoveryService.getStatement(EMPLOYEE).totalOutstanding()).isEqualByComparingTo("60000");
        assertThat(recoveryService.getOutstanding(false)).hasSize(1);
        assertThat(recoveryService.outstandingByEmployee(List.of(EMPLOYEE)).get(EMPLOYEE))
                .isEqualByComparingTo("60000");
        assertThat(recoveryService.getSchedule(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear()).loanDeduction())
                .isEqualByComparingTo("0");

        Payroll payroll = payrollService.generate(payrollRequest());
        assertThat(payroll.getLoanDeduction()).isEqualByComparingTo("0");
        assertThat(payroll.getLoanOpeningBalance()).isEqualByComparingTo("60000");
        assertThat(payroll.getLoanClosingBalance()).isEqualByComparingTo("60000");
        assertThat(recoveryRepository.findById(loan.id()).orElseThrow().getOutstandingAmount())
                .isEqualByComparingTo("60000");
    }

    @Test
    @DisplayName("a recovery closed by a repayment reopens when that payroll is superseded")
    void reversalReopensAClosedRecovery() {
        issueLoan(new BigDecimal("4000"), new BigDecimal("4000"));

        payrollService.generate(payrollRequest());
        assertThat(onlyRecovery().getStatus()).isEqualTo(RecoveryStatus.CLOSED);

        payrollService.regenerate(payrollRequest());

        // Reopened and re-recovered by the new revision - had the status not
        // moved back with the balance, the next period would have skipped a
        // loan that was owed again.
        EmployeeRecovery after = onlyRecovery();
        assertThat(after.getStatus()).isEqualTo(RecoveryStatus.CLOSED);
        assertThat(after.getOutstandingAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an instalment the period cannot afford is recovered as far as it goes "
            + "and the rest stays outstanding for the next period")
    void anUnaffordableInstalmentIsDeferredNotLost() {
        // A loan far larger than one month's pay: the instalment cannot
        // possibly be met in full, and net pay must not go negative to try.
        issueLoan(new BigDecimal("500000"), new BigDecimal("500000"));

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getNetSalary()).isEqualByComparingTo("0.00");
        assertThat(payroll.getLoanDeduction()).isLessThan(new BigDecimal("500000"));
        assertThat(payroll.getRecoveryCarriedForward()).isGreaterThan(BigDecimal.ZERO);

        EmployeeRecovery recovery = onlyRecovery();
        assertThat(recovery.getStatus()).isEqualTo(RecoveryStatus.ACTIVE);
        assertThat(recovery.getOutstandingAmount())
                .isEqualByComparingTo(new BigDecimal("500000").subtract(payroll.getLoanDeduction()));
    }

    @Test
    @DisplayName("the net-pay floor caps what recoveries may take, and the remainder carries forward")
    void theNetPayFloorCapsRecoveries() {
        setNetPayFloor(new BigDecimal("50"));
        issueLoan(new BigDecimal("500000"), new BigDecimal("500000"));

        Payroll payroll = payrollService.generate(payrollRequest());

        // At least half of what was earned survives every recovery.
        BigDecimal floor = payroll.getTotalEarnings().multiply(new BigDecimal("0.50"));
        assertThat(payroll.getNetSalary()).isGreaterThanOrEqualTo(floor);
        assertThat(payroll.getRuleRecoveryMinimumNetPercent()).isEqualByComparingTo("50.00");
        assertThat(payroll.getRecoveryCarriedForward()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("an explicit amount overrides the schedule for that period only, and an explicit "
            + "zero really does skip the instalment")
    void anExplicitAmountOverridesTheSchedule() {
        issueLoan(new BigDecimal("60000"), new BigDecimal("5000"));

        PayrollRequest request = payrollRequest();
        request.setLoanDeduction(new BigDecimal("1500"));
        Payroll payroll = payrollService.generate(request);

        assertThat(payroll.getLoanDeduction()).isEqualByComparingTo("1500.00");
        assertThat(onlyRecovery().getOutstandingAmount()).isEqualByComparingTo("58500.00");

        PayrollRequest skipped = payrollRequest();
        skipped.setLoanDeduction(BigDecimal.ZERO);
        Payroll second = payrollService.regenerate(skipped);

        // Zero is an instruction, not an absent field: the instalment is
        // skipped and the balance is exactly what it was before any run.
        assertThat(second.getLoanDeduction()).isEqualByComparingTo("0.00");
        assertThat(onlyRecovery().getOutstandingAmount()).isEqualByComparingTo("60000.00");
    }

    @Test
    @DisplayName("an ad-hoc fine larger than the period can afford raises a recovery "
            + "so the next period collects the rest")
    void anUnaffordableAdHocFineIsCarriedForward() {
        PayrollRequest request = payrollRequest();
        request.setFine(new BigDecimal("400000"));

        Payroll payroll = payrollService.generate(request);

        assertThat(payroll.getFine()).isLessThan(new BigDecimal("400000"));
        assertThat(payroll.getNetSalary()).isEqualByComparingTo("0.00");

        // Nothing was on file to hold the shortfall, so payroll raised one -
        // otherwise the unrecovered part of the fine would simply vanish.
        List<EmployeeRecovery> raised = recoveryRepository.findAllByEmployeeIdAndStatus(
                EMPLOYEE, RecoveryStatus.ACTIVE);
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getType()).isEqualTo(RecoveryType.FINE);
        assertThat(raised.get(0).getOutstandingAmount())
                .isEqualByComparingTo(new BigDecimal("400000").subtract(payroll.getFine()));
        assertThat(YearMonth.of(raised.get(0).getStartYear(), raised.get(0).getStartMonth()))
                .isEqualTo(PERIOD.plusMonths(1));
    }

    @Test
    @DisplayName("superseding the payroll that carried an amount forward withdraws that recovery too")
    void supersedingWithdrawsACarriedForwardRecovery() {
        PayrollRequest request = payrollRequest();
        request.setFine(new BigDecimal("400000"));
        payrollService.generate(request);
        assertThat(recoveryRepository.findAllByEmployeeIdAndStatus(EMPLOYEE, RecoveryStatus.ACTIVE)).hasSize(1);

        // Regenerate without the fine: the carried-forward amount was created
        // by a run that no longer exists, so leaving it would bill the
        // employee for a fine no payroll ever levied.
        payrollService.regenerate(payrollRequest());

        assertThat(recoveryRepository.findAllByEmployeeIdAndStatus(EMPLOYEE, RecoveryStatus.ACTIVE)).isEmpty();
        assertThat(recoveryRepository.findAllByEmployeeIdOrderByIssuedOnDescIdDesc(EMPLOYEE))
                .allMatch(recovery -> recovery.getStatus() == RecoveryStatus.CANCELLED);
    }

    @Test
    @DisplayName("recoveries are taken in type order when the period cannot cover them all")
    void recoveriesAreTakenInTypeOrder() {
        // Deliberately more than one month's pay in total: advance is served
        // first, the loan gets whatever is left, the fine nothing at all.
        recoveryService.issue(recoveryRequest(RecoveryType.ADVANCE, new BigDecimal("8000"), new BigDecimal("8000")));
        recoveryService.issue(recoveryRequest(RecoveryType.LOAN, new BigDecimal("100000"), new BigDecimal("100000")));
        recoveryService.issue(recoveryRequest(RecoveryType.FINE, new BigDecimal("2000"), new BigDecimal("2000")));

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getAdvanceDeduction()).isEqualByComparingTo("8000.00");
        assertThat(payroll.getLoanDeduction()).isGreaterThan(BigDecimal.ZERO);
        assertThat(payroll.getFine()).isEqualByComparingTo("0.00");
        assertThat(payroll.getNetSalary()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a loan is not recovered before its start period")
    void aLoanIsNotRecoveredBeforeItsStartPeriod() {
        RecoveryRequest request = recoveryRequest(RecoveryType.LOAN, new BigDecimal("60000"), new BigDecimal("5000"));
        request.setStartMonth(PERIOD.plusMonths(1).getMonthValue());
        request.setStartYear(PERIOD.plusMonths(1).getYear());
        recoveryService.issue(request);

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getLoanDeduction()).isEqualByComparingTo("0.00");
        assertThat(onlyRecovery().getOutstandingAmount()).isEqualByComparingTo("60000.00");
        assertThat(payroll.getLoanOpeningBalance()).isEqualByComparingTo("60000.00");
        assertThat(payroll.getLoanClosingBalance()).isEqualByComparingTo("60000.00");
    }

    @Test
    @DisplayName("the schedule endpoint reports what payroll is about to take")
    void theScheduleReportsWhatPayrollWillTake() {
        issueLoan(new BigDecimal("60000"), new BigDecimal("5000"));
        recoveryService.issue(recoveryRequest(RecoveryType.ADVANCE, new BigDecimal("3000"), new BigDecimal("3000")));

        RecoveryDtos.Schedule schedule =
                recoveryService.getSchedule(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear());

        assertThat(schedule.loanDeduction()).isEqualByComparingTo("5000.00");
        assertThat(schedule.advanceDeduction()).isEqualByComparingTo("3000.00");
        assertThat(schedule.totalScheduled()).isEqualByComparingTo("8000.00");
        assertThat(schedule.totalOutstanding()).isEqualByComparingTo("63000.00");

        Payroll payroll = payrollService.generate(payrollRequest());
        assertThat(payroll.getLoanDeduction()).isEqualByComparingTo(schedule.loanDeduction());
        assertThat(payroll.getAdvanceDeduction()).isEqualByComparingTo(schedule.advanceDeduction());
    }

    @Test
    @DisplayName("waiving writes off the balance and stops any further recovery")
    void waivingStopsFurtherRecovery() {
        RecoveryDtos.RecoveryRow loan = issueLoan(new BigDecimal("60000"), new BigDecimal("5000"));

        RecoveryAmountRequest waiver = new RecoveryAmountRequest();
        waiver.setAmount(new BigDecimal("60000"));
        waiver.setRemarks("Written off on separation settlement");
        recoveryService.waive(loan.id(), waiver, HR);

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getLoanDeduction()).isEqualByComparingTo("0.00");
        assertThat(onlyRecovery().getStatus()).isEqualTo(RecoveryStatus.CLOSED);
    }

    @Test
    @DisplayName("waiving a paused loan closes it and removes it from the open register")
    void waivingAPausedLoanClosesIt() {
        RecoveryDtos.RecoveryRow loan = issueLoan(new BigDecimal("60000"), new BigDecimal("5000"));
        RecoveryUpdateRequest pause = new RecoveryUpdateRequest();
        pause.setStatus(RecoveryStatus.ON_HOLD);
        recoveryService.update(loan.id(), pause);

        RecoveryAmountRequest waiver = new RecoveryAmountRequest();
        waiver.setAmount(new BigDecimal("60000"));
        waiver.setRemarks("Written off while repayment is paused");
        recoveryService.waive(loan.id(), waiver, HR);

        assertThat(onlyRecovery().getStatus()).isEqualTo(RecoveryStatus.CLOSED);
        assertThat(onlyRecovery().getClosedAt()).isNotNull();
        assertThat(recoveryService.getOutstanding(false)).isEmpty();
    }

    @Test
    @DisplayName("a recovery that has already been repaid cannot be cancelled - it has to be waived")
    void aPartlyRepaidRecoveryCannotBeCancelled() {
        RecoveryDtos.RecoveryRow loan = issueLoan(new BigDecimal("60000"), new BigDecimal("5000"));
        payrollService.generate(payrollRequest());

        assertThatThrownBy(() -> recoveryService.cancel(loan.id(), "mistake", HR))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("waive");
    }

    @Test
    @DisplayName("the salary slip shows the opening balance, what was recovered and what is left")
    void theSlipShowsTheLoanBalance() {
        issueLoan(new BigDecimal("60000"), new BigDecimal("5000"));
        payrollService.generate(payrollRequest());

        SalarySlipResponse slip =
                salarySlipService.getSlip(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear());

        assertThat(slip.deductions()).anyMatch(line -> line.label().equals("Loan"));
        SalarySlipResponse.RecoveryBalance balance = slip.recoveryBalances().stream()
                .filter(row -> row.label().equals("Loan")).findFirst().orElseThrow();
        assertThat(balance.openingBalance()).isEqualByComparingTo("60000.00");
        assertThat(balance.recovered()).isEqualByComparingTo("5000.00");
        assertThat(balance.closingBalance()).isEqualByComparingTo("55000.00");
    }

    @Test
    @DisplayName("an employee with nothing outstanding gets an unchanged slip")
    void aSlipWithoutRecoveriesIsUnchanged() {
        payrollService.generate(payrollRequest());

        SalarySlipResponse slip =
                salarySlipService.getSlip(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear());

        assertThat(slip.recoveryBalances()).isEmpty();
        assertThat(slip.deductions()).noneMatch(line -> line.label().equals("Loan"));
    }

    // ---- fixtures -----------------------------------------------------------

    private RecoveryDtos.RecoveryRow issueLoan(BigDecimal principal, BigDecimal installment) {
        return recoveryService.issue(recoveryRequest(RecoveryType.LOAN, principal, installment));
    }

    private RecoveryRequest recoveryRequest(RecoveryType type, BigDecimal principal, BigDecimal installment) {
        RecoveryRequest request = new RecoveryRequest();
        request.setEmployeeId(EMPLOYEE);
        request.setType(type);
        request.setPrincipalAmount(principal);
        request.setInstallmentAmount(installment);
        request.setIssuedOn(PERIOD.atDay(1));
        // Recoverable from the period under test regardless of the type's own
        // default start, so each case tests recovery rather than scheduling.
        request.setStartMonth(PERIOD.getMonthValue());
        request.setStartYear(PERIOD.getYear());
        request.setIssuedBy(HR);
        return request;
    }

    private EmployeeRecovery onlyRecovery() {
        List<EmployeeRecovery> all = recoveryRepository.findAllByEmployeeIdOrderByIssuedOnDescIdDesc(EMPLOYEE);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private PayrollRequest payrollRequest() {
        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(EMPLOYEE);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        return request;
    }

    private void generateAttendance() {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of(EMPLOYEE));
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    /**
     * Saved through the repository, not by mutating what {@code getActiveRule}
     * returns - that entity is detached once its read transaction ends
     * ({@code spring.jpa.open-in-view=false}), so an in-place edit would be
     * silently discarded and the test would pass or fail for the wrong reason.
     */
    private void setNetPayFloor(BigDecimal percent) {
        SalaryRule rule = salaryRuleService.getActiveRule();
        rule.setRecoveryMinimumNetPercent(percent);
        salaryRuleRepository.save(rule);
    }

    private void resetNetPayFloor() {
        setNetPayFloor(BigDecimal.ZERO);
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
