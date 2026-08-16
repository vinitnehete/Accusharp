package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
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
 * only a fixed monthly expectation ({@code dayWiseDaysInMonth x
 * standardHoursPerDay}, 208h at the defaults) the month's total hours are
 * compared to. Before this fix, {@code PayrollService} used the attendance
 * engine's daily-summed {@code overtimeHours} unconditionally for every
 * status, which for DAY_WISE understates overtime by exactly {@code
 * (dayWiseDaysInMonth - presentDays) x standardHoursPerDay} hours - a worker
 * present fewer days than the 26-day base looks like they worked *less*
 * overtime than they actually did, purely because fewer days were summed.
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
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private PayrollService payrollService;

    private long punchId = 700_000;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();

        saveEmployee(HR, "EMP-HR-700", "HR Head", Role.HR, EmployeeStatus.PERMANENT,
                new BigDecimal("50000"), false);
    }

    @Test
    @DisplayName("DAY_WISE overtime is measured against the fixed monthly base, not days actually present")
    void dayWiseOvertimeUsesTheFixedMonthlyBase() {
        // grossSalary chosen so perDay = 20800/26 = 800, perHour = 800/8 = 100 - exact numbers to assert on.
        String userId = "DW700";
        saveEmployee(userId, "EMP-DW-700", "Day Wise Worker", Role.EMPLOYEE, EmployeeStatus.DAY_WISE,
                new BigDecimal("20800"), true);

        Shift shift = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(480).build());

        // 20 days present, 15h each (06:00-21:00, no break): 300h total.
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
        // Correct: 300 - (26 x 8) = 92h. The pre-fix behavior summed each day's own
        // excess over 8h (20 x 7h = 140h) - what a present-days-based formula gives.
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("92.00");
        assertThat(payroll.getPerHour()).isEqualByComparingTo("100.00");
        // otAllowance = overtimeHours x perHour x overtimeRateMultiplier(1.00).
        assertThat(payroll.getOtAllowance()).isEqualByComparingTo("9200.00");
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
