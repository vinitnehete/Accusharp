package com.accusharp.hrms.employmenttype;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.EmploymentType;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.OvertimeBasis;
import com.accusharp.hrms.enums.PayBasis;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.*;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.payroll.PayrollService;
import org.junit.jupiter.api.AfterEach;
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
 * Employment-type behaviour as configuration rather than a hardcoded enum.
 *
 * <p>The first test is the one that has to hold: <b>an employee with no
 * employment type is paid exactly as before.</b> `EmployeeStatus` and its seven
 * hardcoded payroll branches are still what runs for every existing row, so a
 * running client's next payroll is unchanged by this feature existing. The
 * remaining tests configure a type and show the same code paying differently.
 *
 * <p>Figures throughout are chosen to be exact: gross 20800 gives
 * perDay = 20800/26 = 800 and perHour = 800/8 = 100.
 */
@SpringBootTest
class EmploymentTypePayrollTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 11);
    private static final String HR = "HR900";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private EmploymentTypeRepository employmentTypeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository summaryRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private PayrollService payrollService;

    private long punchId = 900_000;
    private Shift shift;

    @BeforeEach
    void setUp() {
        clean();
        saveEmployee(HR, Role.HR, EmployeeStatus.PERMANENT, null, new BigDecimal("50000"), false);
        shift = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(480).build());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- the guarantee -----------------------------------------------------

    @Test
    @DisplayName("an employee with no employment type is paid by the legacy enum semantics, exactly as before this feature existed")
    void noEmploymentTypeFallsBackToTheLegacyEnum() {
        String userId = "LEGACY900";
        saveEmployee(userId, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null, new BigDecimal("20800"), true);
        workDays(userId, 20, 6, 21);   // 20 days x 15h = 300h

        Payroll payroll = run(userId);

        // Identical to DayWisePayrollOvertimeTest's assertions on the same input:
        // baseHours = min(20, 26) x 8 = 160, worked OT = 300 - 160 = 140.
        assertThat(payroll.getTotalHours()).isEqualByComparingTo("300.00");
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("140.00");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("20");
        assertThat(payroll.getEarnBasicDA()).isEqualByComparingTo("8000.00");
        assertThat(payroll.getLopDays()).isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("a seeded employment type reproducing DAY_WISE pays identically to the enum it replaces")
    void aSeededTypeMatchesTheEnumItReplaces() {
        String legacyUser = "CMP900A";
        String typedUser = "CMP900B";
        saveEmployee(legacyUser, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null, new BigDecimal("20800"), true);
        saveEmployee(typedUser, Role.EMPLOYEE, EmployeeStatus.DAY_WISE,
                save(dayWiseType("DAY_WISE", null)), new BigDecimal("20800"), true);

        workDays(legacyUser, 20, 6, 21);
        workDays(typedUser, 20, 6, 21);

        Payroll legacy = run(legacyUser);
        Payroll typed = run(typedUser);

        assertThat(typed.getOvertimeHours()).isEqualByComparingTo(legacy.getOvertimeHours());
        assertThat(typed.getPayableDays()).isEqualByComparingTo(legacy.getPayableDays());
        assertThat(typed.getEarnBasicDA()).isEqualByComparingTo(legacy.getEarnBasicDA());
        assertThat(typed.getNetSalary()).isEqualByComparingTo(legacy.getNetSalary());
    }

    // ---- what a company can now change -------------------------------------

    @Test
    @DisplayName("a type with its own payable-days cap prorates against that, not the company-wide 26")
    void payableDaysCapIsPerTypeNotPerCompany() {
        String userId = "CAP900";
        saveEmployee(userId, Role.EMPLOYEE, EmployeeStatus.DAY_WISE,
                save(dayWiseType("DAY_WISE_24", 24)), new BigDecimal("20800"), true);
        workDays(userId, 20, 6, 21);   // 20 days x 15h = 300h

        Payroll payroll = run(userId);

        // basicDA 10400 x 20 payable / 24 base = 8666.67, where the 26-day base
        // gave 8000.00. Two day-wise populations can now differ, which one
        // company-wide salaryRule.dayWiseDaysInMonth could never express.
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("20");
        assertThat(payroll.getEarnBasicDA()).isEqualByComparingTo("8666.67");
        // The overtime baseline uses the same cap: min(20, 24) x 8 = 160,
        // so 300 - 160 = 140 here, unchanged - the cap only bites above 24 days.
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("140.00");
    }

    @Test
    @DisplayName("the cap changes the overtime baseline too, once attendance exceeds it")
    void payableDaysCapAlsoMovesTheOvertimeBaseline() {
        String userId = "CAP901";
        saveEmployee(userId, Role.EMPLOYEE, EmployeeStatus.DAY_WISE,
                save(dayWiseType("DAY_WISE_24", 24)), new BigDecimal("20800"), true);
        workDays(userId, 28, 6, 16);   // 28 days x 10h = 280h

        Payroll payroll = run(userId);

        // baseHours = min(28, 24) x 8 = 192, so OT = 280 - 192 = 88.
        // On the seeded 26-day cap the same attendance gives 280 - 208 = 72.
        assertThat(payroll.getTotalHours()).isEqualByComparingTo("280.00");
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("88.00");
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("24");
    }

    @Test
    @DisplayName("a per-attended-day type can be configured to stop crediting paid leave as overtime")
    void paidLeaveOvertimeIsPerType() {
        String userId = "NOOT900";
        EmploymentType noLeaveOvertime = save(EmploymentType.builder()
                .typeCode("DAY_WISE_NO_LEAVE_OT").typeName("Day wise, no leave OT")
                .payBasis(PayBasis.PER_ATTENDED_DAY).payableDaysCap(26)
                .lopApplies(false).paidLeaveAddsPayableDays(false)
                .overtimeBasis(OvertimeBasis.MONTHLY_TOTAL_HOURS)
                .paidLeaveEarnsOvertime(false)   // the change
                .segmentedRevisionEarnings(false).autoRosterDefaultShift(false).active(true)
                .build());
        saveEmployee(userId, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, noLeaveOvertime,
                new BigDecimal("20800"), true);
        workDays(userId, 20, 6, 21);

        Payroll payroll = run(userId);

        // No leave in this month, so the figure matches - the point is that the
        // flag is now readable and settable rather than implied by the enum.
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("140.00");
    }

    @Test
    @DisplayName("a calendar-day type still prorates against the month and still takes LOP from attendance")
    void calendarDayTypeIsUnchangedInShape() {
        String userId = "PERM900";
        saveEmployee(userId, Role.EMPLOYEE, EmployeeStatus.PERMANENT,
                save(EmploymentType.builder()
                        .typeCode("STAFF_MONTHLY").typeName("Staff monthly")
                        .payBasis(PayBasis.PER_CALENDAR_DAY_LESS_LOP)
                        .lopApplies(true).paidLeaveAddsPayableDays(true)
                        .overtimeBasis(OvertimeBasis.PER_DAY_SHIFT_EXCESS)
                        .paidLeaveEarnsOvertime(false).segmentedRevisionEarnings(true)
                        .autoRosterDefaultShift(true).active(true).build()),
                new BigDecimal("26000"), true);
        // 10 days rostered, only 5 of them worked - LOP is measured against the
        // ROSTER, not the calendar (Attendance.md section 4: days with no roster
        // row are not counted at all), so the unworked 5 are the loss of pay.
        rosterDays(userId, 10);
        punchDays(userId, 5, 6, 19);   // 5 days x 13h = 65h, 5 x 5h daily-summed OT

        Payroll payroll = run(userId);

        // Daily-summed overtime, not the monthly formula - the branch is driven
        // by overtimeBasis now, and this asserts it did not quietly flip.
        assertThat(payroll.getTotalHours()).isEqualByComparingTo("65.00");
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("25.00");
        // 10 working days, 5 present, no paid leave: LOP is the 5 not worked.
        assertThat(payroll.getLopDays()).isEqualByComparingTo("5.0");
        // Prorated against November's 30 calendar days, not the 10 rostered ones.
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("25.0");
    }

    // ---- helpers -----------------------------------------------------------

    private void clean() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        summaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        employmentTypeRepository.deleteAll();
        shiftRepository.deleteAll();
    }

    private EmploymentType dayWiseType(String code, Integer cap) {
        return EmploymentType.builder()
                .typeCode(code).typeName(code)
                .payBasis(PayBasis.PER_ATTENDED_DAY).payableDaysCap(cap)
                .lopApplies(false).paidLeaveAddsPayableDays(false)
                .overtimeBasis(OvertimeBasis.MONTHLY_TOTAL_HOURS)
                .paidLeaveEarnsOvertime(true)
                .segmentedRevisionEarnings(false).autoRosterDefaultShift(false).active(true)
                .build();
    }

    private EmploymentType save(EmploymentType type) {
        return employmentTypeRepository.save(type);
    }

    /** Rosters and works the same N days - the common case. */
    private void workDays(String userId, int days, int inHour, int outHour) {
        rosterDays(userId, days);
        punchDays(userId, days, inHour, outHour);
    }

    private void rosterDays(String userId, int days) {
        List<ShiftSchedule> roster = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            roster.add(ShiftSchedule.builder()
                    .userId(userId).shiftDate(PERIOD.atDay(day)).shift(shift).weekOff(false).build());
        }
        shiftScheduleRepository.saveAll(roster);
    }

    private void punchDays(String userId, int days, int inHour, int outHour) {
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            LocalDate date = PERIOD.atDay(day);
            punches.add(punch(userId, date.atTime(inHour, 0)));
            punches.add(punch(userId, date.atTime(outHour, 0)));
        }
        deviceLogRepository.saveAll(punches);
    }

    private Payroll run(String userId) {
        AttendanceGenerationRequest generation = new AttendanceGenerationRequest();
        generation.setMonth(PERIOD);
        generation.setUserIds(List.of(userId));
        generation.setGeneratedBy(HR);
        attendanceService.generate(generation);

        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(userId);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        return payrollService.generate(request);
    }

    private void saveEmployee(String userId, Role role, EmployeeStatus status,
                              EmploymentType type, BigDecimal gross, boolean overtimeEligible) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .status(status).employmentType(type)
                .recordStatus(RecordStatus.ACTIVE).role(role)
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
