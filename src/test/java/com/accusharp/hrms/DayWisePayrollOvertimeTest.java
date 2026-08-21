package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
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
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.leave.LeaveService;
import com.accusharp.hrms.service.payroll.PayrollService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DAY_WISE has no fixed daily shift to measure each day's overtime against -
 * only a monthly expectation the month's total hours are compared to.
 *
 * <p>Two parts, kept separate:
 * <ul>
 *   <li>Worked-hours OT: {@code totalHours - (min(presentDays,
 *       dayWiseDaysInMonth) x standardHoursPerDay)} - present days alone
 *       capped at {@code dayWiseDaysInMonth} (26 by default), since a
 *       day-wise worker with no weekly off at all can be present more days
 *       than one standard month.</li>
 *   <li>Paid-leave OT: {@code paidLeaveDays x standardHoursPerDay}, added on
 *       top unconditionally - approved paid leave must always add its own
 *       hours to overtime, not just fill headroom under the present-days
 *       cap. An earlier version of this formula capped {@code presentDays +
 *       paidLeaveDays} together, which let leave get silently absorbed by
 *       the cap for any employee already present close to 26 days - the
 *       common case - so leave almost never actually counted.</li>
 * </ul>
 * Before either fix, the baseline was the flat {@code dayWiseDaysInMonth x
 * standardHoursPerDay} regardless of attendance or leave.
 */
@SpringBootTest
class DayWisePayrollOvertimeTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 11);
    private static final String HR = "HR700";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private LeaveService leaveService;
    @Autowired private PayrollService payrollService;

    private long punchId = 700_000;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();

        saveEmployee(HR, "EMP-HR-700", "HR Head", Role.HR, EmployeeStatus.PERMANENT,
                new BigDecimal("50000"), false);
    }

    @Test
    @DisplayName("DAY_WISE overtime shrinks the baseline when present days are fewer than the cap")
    void dayWiseOvertimeShrinksTheBaselineWhenPresentDaysAreFewerThanTheCap() {
        // grossSalary chosen so perDay = 20800/26 = 800, perHour = 800/8 = 100 - exact numbers to assert on.
        String userId = "DW700";
        saveEmployee(userId, "EMP-DW-700", "Day Wise Worker", Role.EMPLOYEE, EmployeeStatus.DAY_WISE,
                new BigDecimal("20800"), true);

        Shift shift = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(480).build());

        // 20 days present, 15h each (06:00-21:00, no break): 300h total, 0 leave.
        List<ShiftSchedule> roster = new ArrayList<>();
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= 20; day++) {
            LocalDate date = PERIOD.atDay(day);
            roster.add(ShiftSchedule.builder().userId(userId).shiftDate(date).shift(shift).weekOff(false).build());
            punches.add(punch(userId, date.atTime(6, 0)));
            punches.add(punch(userId, date.atTime(21, 0)));
        }
        shiftScheduleRepository.saveAll(roster);
        deviceLogRepository.saveAll(punches);

        generateAttendance(userId);
        Payroll payroll = payrollService.generate(payrollRequest(userId));

        // 20 days x 15h = 300h total.
        assertThat(payroll.getTotalHours()).isEqualByComparingTo("300.00");
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("20");
        assertThat(payroll.getPaidLeaveDays()).isEqualByComparingTo("0");
        // baseHours = min(20, 26) x 8 = 160; worked OT = 300 - 160 = 140.
        // Leave OT = 0 x 8 = 0. Total = 140h. The old flat-208h baseline gave
        // 92h here - it charged this worker for 6 days they were never even rostered for.
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("140.00");
        assertThat(payroll.getPerHour()).isEqualByComparingTo("100.00");
        // otAllowance = overtimeHours x perHour x overtimeRateMultiplier(1.00).
        assertThat(payroll.getOtAllowance()).isEqualByComparingTo("14000.00");
        // earnBasicDA = basicDA x payableDays / totalDays. basicDA = 50% of
        // 20800 gross = 10400; payableDays = min(20 + 0, 26) = 20.
        // 10400 x 20 / 26 = 8000.00 - paid for exactly the days worked, no more.
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("20");
        assertThat(payroll.getEarnBasicDA()).isEqualByComparingTo("8000.00");
    }

    @Test
    @DisplayName("DAY_WISE approved paid leave adds its own hours to overtime, on top of worked-hours OT")
    void dayWiseOvertimeAddsPaidLeaveHoursOnTopOfWorkedOvertime() {
        String userId = "DW701";
        saveEmployee(userId, "EMP-DW-701", "Day Wise Worker Two", Role.EMPLOYEE, EmployeeStatus.DAY_WISE,
                new BigDecimal("20800"), true);

        Shift shift = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(480).build());

        // 28 rostered days: 25 present at 10h each (06:00-16:00), the last 3 on approved leave.
        List<ShiftSchedule> roster = new ArrayList<>();
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= 28; day++) {
            LocalDate date = PERIOD.atDay(day);
            roster.add(ShiftSchedule.builder().userId(userId).shiftDate(date).shift(shift).weekOff(false).build());
            if (day <= 25) {
                punches.add(punch(userId, date.atTime(6, 0)));
                punches.add(punch(userId, date.atTime(16, 0)));
            }
        }
        shiftScheduleRepository.saveAll(roster);
        deviceLogRepository.saveAll(punches);

        LeaveRequestPayload payload = new LeaveRequestPayload();
        payload.setUserId(userId);
        payload.setLeaveType(LeaveType.CASUAL_LEAVE);
        payload.setFromDate(PERIOD.atDay(26));
        payload.setToDate(PERIOD.atDay(28));
        payload.setDuration(LeaveDuration.FULL_DAY);
        payload.setReason("Personal");
        var applied = leaveService.apply(payload);
        LeaveDecisionRequest decision = new LeaveDecisionRequest();
        decision.setApproverId(HR);
        decision.setComments("Approved");
        leaveService.approve(applied.id(), decision);

        generateAttendance(userId);
        Payroll payroll = payrollService.generate(payrollRequest(userId));

        // 25 days x 10h = 250h total.
        assertThat(payroll.getTotalHours()).isEqualByComparingTo("250.00");
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("25");
        assertThat(payroll.getPaidLeaveDays()).isEqualByComparingTo("3");
        // baseHours = min(25, 26) x 8 = 200 (presentDays alone, under the cap);
        // worked OT = 250 - 200 = 50. Leave OT = 3 x 8 = 24, added on top, never
        // clipped by the 26-day cap. Total = 50 + 24 = 74h.
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("74.00");
        assertThat(payroll.getOtAllowance()).isEqualByComparingTo("7400.00");
        // earnBasicDA = basicDA x payableDays / totalDays. basicDA = 50% of
        // 20800 gross = 10400; payableDays = min(25, 26) = 25 - paid leave does
        // NOT add to payableDays, only to overtime (asserted above). So this
        // employee earns 25/26 of basicDA, not the full amount: 10400 x 25 / 26
        // = 10000.00. The 3 leave days already earned their own overtime credit;
        // they don't also earn a share of the fixed structure.
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("25");
        assertThat(payroll.getEarnBasicDA()).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("DAY_WISE worked-hours baseline is capped at dayWiseDaysInMonth when present days alone exceed it")
    void dayWiseOvertimeBaselineIsCappedWhenPresentDaysAloneExceedIt() {
        String userId = "DW702";
        saveEmployee(userId, "EMP-DW-702", "Day Wise Worker Three", Role.EMPLOYEE, EmployeeStatus.DAY_WISE,
                new BigDecimal("20800"), true);

        Shift shift = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(480).build());

        // 28 days present (no weekly off at all), 10h each (06:00-16:00): 280h total, 0 leave.
        List<ShiftSchedule> roster = new ArrayList<>();
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= 28; day++) {
            LocalDate date = PERIOD.atDay(day);
            roster.add(ShiftSchedule.builder().userId(userId).shiftDate(date).shift(shift).weekOff(false).build());
            punches.add(punch(userId, date.atTime(6, 0)));
            punches.add(punch(userId, date.atTime(16, 0)));
        }
        shiftScheduleRepository.saveAll(roster);
        deviceLogRepository.saveAll(punches);

        generateAttendance(userId);
        Payroll payroll = payrollService.generate(payrollRequest(userId));

        // 28 days x 10h = 280h total.
        assertThat(payroll.getTotalHours()).isEqualByComparingTo("280.00");
        // attendance recorded 28 present days, but the stored/displayed figure
        // on payroll is capped at dayWiseDaysInMonth (26) to match payableDays -
        // this worker is never paid or measured against more than one standard month.
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("26");
        assertThat(payroll.getPaidLeaveDays()).isEqualByComparingTo("0");
        // baseHours = min(28, 26) x 8 = 208 (capped - 28 present days exceeds one
        // standard month); worked OT = 280 - 208 = 72. Leave OT = 0. Total = 72h.
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("72.00");
        assertThat(payroll.getOtAllowance()).isEqualByComparingTo("7200.00");
        // earnBasicDA = basicDA x payableDays / totalDays = 10400 x 26 / 26 =
        // 10400.00 - the cap already applied here even before this fix, via
        // payableDays; this just makes the presentDays figure agree with it.
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("26");
        assertThat(payroll.getEarnBasicDA()).isEqualByComparingTo("10400.00");
    }

    @Test
    @DisplayName("PERMANENT overtime is untouched - still the attendance engine's daily-summed value")
    void permanentOvertimeStillUsesTheDailySummedValue() {
        String userId = "PM700";
        saveEmployee(userId, "EMP-PM-700", "Permanent Worker", Role.EMPLOYEE, EmployeeStatus.PERMANENT,
                new BigDecimal("26000"), true);

        Shift shift = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(480).build());

        // 5 days present, 13h each (06:00-19:00, no break): 65h total, 5 x 5h = 25h daily-summed overtime.
        // Applying the DAY_WISE monthly-base formula here (65 - 30x8) would go negative/clamp to 0 -
        // this test fails loudly if the branch in PayrollService ever stops being DAY_WISE-only.
        List<ShiftSchedule> roster = new ArrayList<>();
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            LocalDate date = PERIOD.atDay(day);
            roster.add(ShiftSchedule.builder().userId(userId).shiftDate(date).shift(shift).weekOff(false).build());
            punches.add(punch(userId, date.atTime(6, 0)));
            punches.add(punch(userId, date.atTime(19, 0)));
        }
        shiftScheduleRepository.saveAll(roster);
        deviceLogRepository.saveAll(punches);

        generateAttendance(userId);
        Payroll payroll = payrollService.generate(payrollRequest(userId));

        assertThat(payroll.getTotalHours()).isEqualByComparingTo("65.00");
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("25.00");
    }

    // ---- helpers -----------------------------------------------------------

    private void generateAttendance(String userId) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of(userId));
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    private PayrollRequest payrollRequest(String userId) {
        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(userId);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        return request;
    }

    private void saveEmployee(String userId, String code, String name, Role role, EmployeeStatus status,
                              BigDecimal gross, boolean overtimeEligible) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode(code).employeeName(name)
                .status(status).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(gross).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(overtimeEligible)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }

    private DeviceLog punch(String userId, LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(userId).logDate(at).build();
    }
}
