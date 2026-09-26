package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.PayrollAuditDtos;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Holiday;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.service.report.PayrollAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mandatory holiday is a paid day for a DAY_WISE worker too.
 *
 * <p>A salaried employee was always paid for it: they are paid the whole
 * calendar month less LOP, and a holiday is never LOP. A DAY_WISE worker is
 * paid per attended working day, and a holiday is not a working day, so the
 * holiday showed on the attendance and then paid nothing.
 *
 * <p>August 2026 starts on a Saturday; the 15th (Independence Day) is a
 * Saturday and its Sundays are the 2nd, 9th, 16th, 23rd and 30th. The roster
 * covers the whole month with Sundays as the weekly off, so there are 26
 * rostered working days before any holiday. Gross 20800 makes the DAY_WISE
 * per-day rate 20800 / 26 = 800.
 */
@SpringBootTest
class DayWiseHolidayPayTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 8);
    private static final LocalDate INDEPENDENCE_DAY = PERIOD.atDay(15);
    private static final String HR = "HR750";
    private static final String WORKER = "DW750";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private PayrollService payrollService;
    @Autowired private PayrollAuditService payrollAuditService;

    private Shift general;
    private long punchId = 750_000;

    @BeforeEach
    void setUp() {
        clean();
        punchId = 750_000;

        general = shiftRepository.save(Shift.builder()
                .shiftCode("HOLGENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240).build());

        saveEmployee(HR, Role.HR, EmployeeStatus.PERMANENT, null);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("DAY_WISE: a mandatory holiday on a working day is paid as one day")
    void dayWiseIsPaidForAMandatoryHoliday() {
        Employee worker = saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);
        rosterWholeMonth(WORKER);
        mandatoryHoliday(INDEPENDENCE_DAY);
        // Present on the first 20 working days, never on the holiday itself.
        workFullDays(WORKER, 20, date -> !date.equals(INDEPENDENCE_DAY));

        generateAttendance(WORKER);
        Payroll payroll = payrollService.generate(payrollRequest(WORKER));

        // presentDays is still what they attended; the holiday is added to
        // payable days only, so overtime does not move: 160h - 20 x 8h = 0.
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("20");
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("0");
        // 20 attended + 1 holiday. Before the fix this was 20.
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("21");
        // basicDA x 21 / 26 - the holiday earns its share of the structure.
        assertThat(payroll.getEarnBasicDA()).isEqualByComparingTo(
                salaryCalculationService.prorate(worker.getBasicDA(), new BigDecimal("26"), new BigDecimal("21")));

        // The day-wise audit pays the same day, and still adds up to the run.
        PayrollAuditDtos.DayWiseReport report =
                payrollAuditService.dayWiseReport(WORKER, PERIOD.getMonthValue(), PERIOD.getYear());
        PayrollAuditDtos.DayWiseRow holiday = dayRow(report, INDEPENDENCE_DAY);
        assertThat(holiday.holiday()).isTrue();
        assertThat(holiday.paidFraction()).isEqualByComparingTo("1.0");
        assertThat(holiday.dayWage()).isEqualByComparingTo("800.00");
        assertThat(report.dayWisePaidDays()).isEqualByComparingTo("21.0");
        assertThat(report.reconciled()).isTrue();
    }

    @Test
    @DisplayName("DAY_WISE: a holiday that falls on the weekly off adds nothing")
    void aHolidayOnTheWeeklyOffIsNotPaidAgain() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);
        rosterWholeMonth(WORKER);
        mandatoryHoliday(PERIOD.atDay(16)); // a Sunday - already their day off
        workFullDays(WORKER, 20, date -> true);

        generateAttendance(WORKER);
        Payroll payroll = payrollService.generate(payrollRequest(WORKER));

        assertThat(payroll.getPayableDays()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("DAY_WISE: a holiday they worked is still paid, and the hours still count as overtime")
    void aWorkedHolidayIsPaidAndItsHoursStayOvertime() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);
        rosterWholeMonth(WORKER);
        mandatoryHoliday(INDEPENDENCE_DAY);
        workFullDays(WORKER, 20, date -> !date.equals(INDEPENDENCE_DAY));
        workFullDay(WORKER, INDEPENDENCE_DAY);

        generateAttendance(WORKER);
        Payroll payroll = payrollService.generate(payrollRequest(WORKER));

        // The worked holiday is not a working day, so it is not in presentDays
        // and its 8 hours are overtime exactly as before: 168h - 20 x 8h.
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("20");
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("8");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("21");
    }

    @Test
    @DisplayName("DAY_WISE: a holiday after the relieving date is not paid")
    void aLeaverIsNotPaidAHolidayAfterRelieving() {
        Employee worker = saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, PERIOD.atDay(10));
        rosterWholeMonth(worker.getUserId()); // roster written before they left
        mandatoryHoliday(INDEPENDENCE_DAY);
        // 1st, 3rd-8th and 10th: the 8 working days up to relieving.
        workFullDays(WORKER, 8, date -> true);

        generateAttendance(WORKER);
        Payroll payroll = payrollService.generate(payrollRequest(WORKER));

        assertThat(payroll.getPayableDays()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("DAY_WISE at the cap: the worked day the holiday pushes past the cap is paid as overtime")
    void atTheCapTheWorkedDayTheHolidayPushesOutIsOvertime() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);
        // No weekly off at all: 30 working days besides the holiday.
        rosterWholeMonth(WORKER, date -> false);
        mandatoryHoliday(INDEPENDENCE_DAY);
        // 1st-14th and 16th-27th, 8 hours each: 208 hours.
        workFullDays(WORKER, 26, date -> !date.equals(INDEPENDENCE_DAY));

        generateAttendance(WORKER);
        Payroll payroll = payrollService.generate(payrollRequest(WORKER));

        // 26 attended + 1 holiday, still capped at the 26-day base.
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("26");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("26");
        // The holiday takes one of the 26 slots, so only 25 attended days fit
        // in the base: 208h - 25 x 8h = 8h. Before, this was 208h - 26 x 8h = 0
        // and one full day's work was paid nothing.
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("8");
        // 8h x 100 per hour (800 per day / 8) - exactly one day's wage.
        assertThat(payroll.getOtAllowance()).isEqualByComparingTo("800.00");

        // The day table stops at the cap too, so it adds up to the run again:
        // the last worked day's wage is in the month's overtime instead.
        PayrollAuditDtos.DayWiseReport report =
                payrollAuditService.dayWiseReport(WORKER, PERIOD.getMonthValue(), PERIOD.getYear());
        assertThat(report.dayWisePaidDays()).isEqualByComparingTo("26.0");
        assertThat(report.reconciled()).isTrue();
        assertThat(dayRow(report, INDEPENDENCE_DAY).paidFraction()).isEqualByComparingTo("1.0");
        assertThat(dayRow(report, PERIOD.atDay(26)).paidFraction()).isEqualByComparingTo("1.0");
        assertThat(dayRow(report, PERIOD.atDay(27)).paidFraction()).isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("DAY_WISE present past the cap with no holiday: overtime is unchanged")
    void pastTheCapWithNoHolidayOvertimeIsUnchanged() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);
        rosterWholeMonth(WORKER, date -> false);
        // 28 days at 8 hours: 224 hours.
        workFullDays(WORKER, 28, date -> true);

        generateAttendance(WORKER);
        Payroll payroll = payrollService.generate(payrollRequest(WORKER));

        assertThat(payroll.getPayableDays()).isEqualByComparingTo("26");
        // 224h - 26 x 8h, as it always was.
        assertThat(payroll.getOvertimeHours()).isEqualByComparingTo("16");
    }

    @Test
    @DisplayName("PERMANENT: holiday pay is unchanged - the full month, no LOP")
    void permanentHolidayPayIsUnchanged() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.PERMANENT, null);
        rosterWholeMonth(WORKER);
        mandatoryHoliday(INDEPENDENCE_DAY);
        workFullDays(WORKER, 25, date -> !date.equals(INDEPENDENCE_DAY));

        generateAttendance(WORKER);
        Payroll payroll = payrollService.generate(payrollRequest(WORKER));

        assertThat(payroll.getWorkingDays()).isEqualTo(25);
        assertThat(payroll.getLopDays()).isEqualByComparingTo("0");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("31");
    }

    // ---- helpers -----------------------------------------------------------

    private PayrollAuditDtos.DayWiseRow dayRow(PayrollAuditDtos.DayWiseReport report, LocalDate date) {
        return report.days().stream().filter(day -> day.date().equals(date)).findFirst().orElseThrow();
    }

    private void rosterWholeMonth(String userId) {
        rosterWholeMonth(userId, date -> date.getDayOfWeek() == DayOfWeek.SUNDAY);
    }

    private void rosterWholeMonth(String userId, Predicate<LocalDate> weekOff) {
        List<ShiftSchedule> roster = new ArrayList<>();
        for (int day = 1; day <= PERIOD.lengthOfMonth(); day++) {
            LocalDate date = PERIOD.atDay(day);
            roster.add(ShiftSchedule.builder()
                    .userId(userId).shiftDate(date).shift(general).weekOff(weekOff.test(date)).build());
        }
        shiftScheduleRepository.saveAll(roster);
    }

    /** Full days on the first {@code count} rostered working days that pass {@code include}. */
    private void workFullDays(String userId, int count, Predicate<LocalDate> include) {
        int worked = 0;
        for (ShiftSchedule day : shiftScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getUserId().equals(userId) && !schedule.isWeekOff())
                .sorted((a, b) -> a.getShiftDate().compareTo(b.getShiftDate()))
                .toList()) {
            if (worked == count) {
                return;
            }
            if (include.test(day.getShiftDate())) {
                workFullDay(userId, day.getShiftDate());
                worked++;
            }
        }
    }

    private void workFullDay(String userId, LocalDate date) {
        deviceLogRepository.saveAll(List.of(punch(userId, date.atTime(9, 0)), punch(userId, date.atTime(18, 0))));
    }

    private void mandatoryHoliday(LocalDate date) {
        holidayRepository.save(Holiday.builder()
                .holidayName("Independence Day").holidayDate(date).optionalHoliday(false).build());
    }

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

    private Employee saveEmployee(String userId, Role role, EmployeeStatus status, LocalDate relievingDate) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .status(status).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .relievingDate(relievingDate)
                .grossSalary(new BigDecimal("20800")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(true)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        return employeeRepository.save(employee);
    }

    private DeviceLog punch(String userId, LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(userId).logDate(at).build();
    }

    private void clean() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        holidayRepository.deleteAll();
    }
}
