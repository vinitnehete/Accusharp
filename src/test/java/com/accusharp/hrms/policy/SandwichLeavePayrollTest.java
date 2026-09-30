package com.accusharp.hrms.policy;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Holiday;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.repository.*;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.leave.LeaveService;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.service.policy.SandwichLeaveEvaluator.Charge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sandwich rule end to end: real attendance generation, real leave
 * applications, a real payroll run.
 *
 * <p>August 2026 has 31 days. The 15th (Independence Day) is a Saturday, the
 * 14th a Friday, the 16th a Sunday and the 17th a Monday. Two rosters are used:
 * one with no weekly off, so the 14th-15th-16th are three days in a row with
 * only the holiday between them; and one with Sundays off, the real calendar.
 */
@SpringBootTest
class SandwichLeavePayrollTest {

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final String HR = "HR900";
    private static final String WORKER = "SW900";
    private static final Predicate<LocalDate> NO_WEEKLY_OFF = date -> false;
    private static final Predicate<LocalDate> SUNDAYS_OFF = date -> date.getDayOfWeek() == DayOfWeek.SUNDAY;

    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository summaryRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private AttendancePolicyRuleRepository policyRuleRepository;
    @Autowired private AttendancePolicyApplicationRepository policyApplicationRepository;
    @Autowired private AttendancePolicyOutcomeRepository policyOutcomeRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private LeaveService leaveService;
    @Autowired private PayrollService payrollService;

    private Company company;
    private Shift shift;
    private long punchId = 900_000;

    @BeforeEach
    void setUp() {
        clean();
        company = companyRepository.save(Company.builder()
                .companyCode("SANDWICH-CO").companyName("Sandwich Co").status(RecordStatus.ACTIVE).build());
        shift = shiftRepository.save(Shift.builder()
                .company(company).shiftCode("SWGENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240).build());
        holidayRepository.save(Holiday.builder().company(company)
                .holidayName("Independence Day").holidayDate(AUG.atDay(15)).optionalHoliday(false).build());
        saveEmployee(HR, Role.HR, EmployeeStatus.PERMANENT);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- 14th, 15th, 16th in a row -----------------------------------------------

    @Test
    @DisplayName("rule not switched on: leave on the 14th and 16th is paid and the holiday is paid - as today")
    void ruleOffChangesNothing() {
        monthlyStaffOnLeave(NO_WEEKLY_OFF, 14, 16);

        Payroll payroll = runPayroll();

        assertThat(payroll.getLopDays()).isEqualByComparingTo("0");
        assertThat(payroll.getPaidLeaveDays()).isEqualByComparingTo("2");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("31");
        assertThat(summary().getSandwichHolidayDays()).isEqualByComparingTo("0");
        assertThat(policyOutcomeRepository.findAll()).isEmpty();
        assertThat(attendanceService.getMonthlyAttendance(WORKER, AUG).sandwich()).isEqualTo(Charge.NONE);
    }

    @Test
    @DisplayName("the month's attendance names the days the rule took, so the records screen can mark them")
    void theMonthNamesTheDaysTaken() {
        switchRuleOn(true);
        monthlyStaffOnLeave(NO_WEEKLY_OFF, 14, 16);
        generateAttendance();

        Charge sandwich = attendanceService.getMonthlyAttendance(WORKER, AUG).sandwich();

        assertThat(sandwich.holidays()).containsExactly(AUG.atDay(15));
        assertThat(sandwich.leaveDates()).containsExactly(AUG.atDay(14), AUG.atDay(16));
    }

    @Test
    @DisplayName("rule on: leave on the 14th and 16th makes the 14th, 15th and 16th all unpaid")
    void leaveOnBothSidesLosesThreeDays() {
        switchRuleOn(true);
        monthlyStaffOnLeave(NO_WEEKLY_OFF, 14, 16);

        Payroll payroll = runPayroll();

        assertThat(payroll.getLopDays()).isEqualByComparingTo("3");
        assertThat(payroll.getPaidLeaveDays()).isEqualByComparingTo("0");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("28");

        MonthlyAttendanceSummary summary = summary();
        assertThat(summary.getSandwichHolidayDays()).isEqualByComparingTo("1");
        assertThat(summary.getPolicyLopDays()).isEqualByComparingTo("3");
        assertThat(policyOutcomeRepository.findAll()).singleElement().satisfies(outcome -> {
            assertThat(outcome.getRuleType()).isEqualTo(RuleType.SANDWICH_LEAVE);
            assertThat(outcome.getExplanation())
                    .contains("holiday lost: 2026-08-15")
                    .contains("paid leave unpaid: 2026-08-14, 2026-08-16")
                    .contains("rule SANDWICH_LEAVE v1 scoped COMPANY");
        });
        // The leave itself stays approved and used - the rule decides pay, not balances.
        assertThat(casualLeaveUsed()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("rule on, but 'leave around the holiday also unpaid' off: only the holiday is lost")
    void keepingAdjacentLeavePaidLosesOnlyTheHoliday() {
        switchRuleOn(false);
        monthlyStaffOnLeave(NO_WEEKLY_OFF, 14, 16);

        Payroll payroll = runPayroll();

        assertThat(payroll.getLopDays()).isEqualByComparingTo("1");
        assertThat(payroll.getPaidLeaveDays()).isEqualByComparingTo("2");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("rule on: leave on the 14th but worked the 16th - one working day is enough, all paid")
    void workingOneSideKeepsEverything() {
        switchRuleOn(true);
        monthlyStaffOnLeave(NO_WEEKLY_OFF, 14);

        Payroll payroll = runPayroll();

        assertThat(payroll.getLopDays()).isEqualByComparingTo("0");
        assertThat(payroll.getPaidLeaveDays()).isEqualByComparingTo("1");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("31");
    }

    @Test
    @DisplayName("rule on: one leave for the 14th to 16th covers the holiday - three days of leave, fully paid")
    void leaveThroughTheHolidayIsPaid() {
        switchRuleOn(true);
        Employee worker = saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.PERMANENT);
        roster(worker, NO_WEEKLY_OFF);
        workEveryWorkingDayExcept(Set.of(14, 15, 16), NO_WEEKLY_OFF);
        applyAndApproveLeave(AUG.atDay(14), AUG.atDay(16));

        Payroll payroll = runPayroll();

        assertThat(payroll.getLopDays()).isEqualByComparingTo("0");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("31");
        // The holiday was taken as leave: three days come off the balance.
        assertThat(casualLeaveUsed()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("rule on: absent without leave on the 14th and 16th - the holiday is lost as well")
    void absentOnBothSidesLosesTheHoliday() {
        switchRuleOn(true);
        Employee worker = saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.PERMANENT);
        roster(worker, NO_WEEKLY_OFF);
        workEveryWorkingDayExcept(Set.of(14, 15, 16), NO_WEEKLY_OFF);

        Payroll payroll = runPayroll();

        // Two absences were already unpaid; the holiday is the third.
        assertThat(payroll.getLopDays()).isEqualByComparingTo("3");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("28");
    }

    // ---- the real calendar: Sunday the 16th is a weekly off -------------------------------

    @Test
    @DisplayName("rule on, Sundays off: leave Friday 14th, worked Monday 17th - the weekly off is looked past, all paid")
    void weeklyOffIsLookedPastWhenWorkedAfter() {
        switchRuleOn(true);
        monthlyStaffOnLeave(SUNDAYS_OFF, 14);

        Payroll payroll = runPayroll();

        assertThat(payroll.getLopDays()).isEqualByComparingTo("0");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("31");
    }

    @Test
    @DisplayName("rule on, Sundays off: leave Friday 14th and Monday 17th - 14th, 15th and 17th unpaid, Sunday still paid")
    void weeklyOffInsideTheSandwichStaysPaid() {
        switchRuleOn(true);
        monthlyStaffOnLeave(SUNDAYS_OFF, 14, 17);

        Payroll payroll = runPayroll();

        assertThat(payroll.getLopDays()).isEqualByComparingTo("3");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("28");
    }

    // ---- a day-wise worker, paid per attended day ---------------------------------------

    @Test
    @DisplayName("rule on, DAY_WISE: absent Friday 14th and Monday 17th - the holiday is not paid on top of attended days")
    void dayWiseWorkerIsNotPaidTheSandwichedHoliday() {
        switchRuleOn(true);
        Employee worker = saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE);
        roster(worker, SUNDAYS_OFF);
        workEveryWorkingDayExcept(Set.of(14, 15, 17), SUNDAYS_OFF);

        Payroll payroll = runPayroll();

        // 25 working days less the two absences. Without the rule this is 24:
        // 23 attended plus the holiday (see DayWiseHolidayPayTest).
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("23");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("23");
    }

    // ---- helpers ------------------------------------------------------------------------

    /** A salaried worker who works every working day except the leave days, which are approved CL. */
    private void monthlyStaffOnLeave(Predicate<LocalDate> weeklyOff, Integer... leaveDays) {
        Employee worker = saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.PERMANENT);
        roster(worker, weeklyOff);
        Set<Integer> off = new HashSet<>(Set.of(leaveDays));
        off.add(15);
        workEveryWorkingDayExcept(off, weeklyOff);
        for (int day : leaveDays) {
            applyAndApproveLeave(AUG.atDay(day), AUG.atDay(day));
        }
    }

    private void switchRuleOn(boolean adjacentLeaveUnpaid) {
        policyRuleRepository.save(AttendancePolicyRule.builder()
                .company(company).scope(RuleScope.COMPANY).scopeRef(RuleScope.ANY)
                .ruleType(RuleType.SANDWICH_LEAVE).version(1)
                .effectiveFrom(AUG.atDay(1)).enabled(true)
                .params("{\"adjacentLeaveUnpaid\":" + adjacentLeaveUnpaid + "}")
                .createdAt(Instant.now()).createdBy(HR)
                .build());
    }

    private void roster(Employee worker, Predicate<LocalDate> weeklyOff) {
        shiftScheduleRepository.saveAll(AUG.atDay(1).datesUntil(AUG.atEndOfMonth().plusDays(1))
                .map(date -> ShiftSchedule.builder().userId(worker.getUserId()).shiftDate(date)
                        .shift(shift).weekOff(weeklyOff.test(date)).build())
                .toList());
    }

    private void workEveryWorkingDayExcept(Set<Integer> daysOff, Predicate<LocalDate> weeklyOff) {
        deviceLogRepository.saveAll(AUG.atDay(1).datesUntil(AUG.atEndOfMonth().plusDays(1))
                .filter(date -> !weeklyOff.test(date) && !daysOff.contains(date.getDayOfMonth()))
                .flatMap(date -> Stream.of(punch(date.atTime(9, 0)), punch(date.atTime(18, 0))))
                .toList());
    }

    private void applyAndApproveLeave(LocalDate from, LocalDate to) {
        LeaveRequestPayload payload = new LeaveRequestPayload();
        payload.setUserId(WORKER);
        payload.setLeaveType(LeaveType.CASUAL_LEAVE);
        payload.setFromDate(from);
        payload.setToDate(to);
        payload.setDuration(LeaveDuration.FULL_DAY);
        payload.setReason("Personal");
        var applied = leaveService.apply(payload);
        LeaveDecisionRequest decision = new LeaveDecisionRequest();
        decision.setApproverId(HR);
        decision.setComments("Approved");
        leaveService.approve(applied.id(), decision);
    }

    private void generateAttendance() {
        AttendanceGenerationRequest generation = new AttendanceGenerationRequest();
        generation.setMonth(AUG);
        generation.setUserIds(List.of(WORKER));
        generation.setGeneratedBy(HR);
        attendanceService.generate(generation);
    }

    private Payroll runPayroll() {
        generateAttendance();

        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(WORKER);
        request.setMonth(AUG.getMonthValue());
        request.setYear(AUG.getYear());
        request.setGeneratedBy(HR);
        return payrollService.generate(request);
    }

    private MonthlyAttendanceSummary summary() {
        return summaryRepository.findByUserIdAndMonth(WORKER, AUG.toString()).orElseThrow();
    }

    private BigDecimal casualLeaveUsed() {
        return leaveBalanceRepository.findByUserIdAndLeaveYearAndLeaveType(WORKER, 2026, LeaveType.CASUAL_LEAVE)
                .orElseThrow().getUsed();
    }

    private Employee saveEmployee(String userId, Role role, EmployeeStatus status) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company).status(status).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("31000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        return employeeRepository.save(employee);
    }

    private DeviceLog punch(LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(WORKER).logDate(at).build();
    }

    private void clean() {
        payrollRepository.deleteAll();
        policyApplicationRepository.deleteAll();
        policyOutcomeRepository.deleteAll();
        policyRuleRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        summaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveCreditRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        holidayRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        companyRepository.deleteAll();
    }
}
