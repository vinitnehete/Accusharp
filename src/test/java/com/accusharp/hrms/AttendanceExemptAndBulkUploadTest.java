package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceBulkUploadResponse;
import com.accusharp.hrms.dto.AttendanceBulkUploadRow;
import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Holiday;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveType;
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
import com.accusharp.hrms.service.leave.LeaveService;
import com.accusharp.hrms.service.payroll.PayrollService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attendance for people the biometric device never sees.
 *
 * <p>The case that matters is the first one: without the exemption, a
 * director who does not punch generates as {@code ABSENT} on every rostered
 * working day, which is a full month of loss of pay and a near-zero net
 * salary. Everything else here guards the edges of the two ways out of that -
 * the standing flag, and the bulk upload that overrides it.
 */
@SpringBootTest
class AttendanceExemptAndBulkUploadTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final String DIRECTOR = "DIR700";
    private static final String WORKER = "EMP700";
    private static final String HR = "HR700";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private HolidayRepository holidayRepository;
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
        holidayRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();

        Shift general = shiftRepository.save(Shift.builder()
                .shiftCode("EX-GEN").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-HR-700", "HR Head", Role.HR, false);
        saveEmployee(DIRECTOR, "EMP-DIR-700", "Senior Director", Role.EMPLOYEE, true);
        saveEmployee(WORKER, "EMP-WRK-700", "Shop Floor Worker", Role.EMPLOYEE, false);

        // Both rostered identically for the whole month, Sundays off. The only
        // difference between them is the exemption flag and who punches.
        List<ShiftSchedule> roster = new ArrayList<>();
        for (String userId : List.of(DIRECTOR, WORKER)) {
            for (int day = 1; day <= PERIOD.lengthOfMonth(); day++) {
                LocalDate date = PERIOD.atDay(day);
                roster.add(ShiftSchedule.builder()
                        .userId(userId).shiftDate(date).shift(general)
                        .weekOff(date.getDayOfWeek() == DayOfWeek.SUNDAY).build());
            }
        }
        shiftScheduleRepository.saveAll(roster);

        // Only the worker punches. The director never touches the device.
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= PERIOD.lengthOfMonth(); day++) {
            LocalDate date = PERIOD.atDay(day);
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            punches.add(punch(WORKER, date.atTime(9, 0)));
            punches.add(punch(WORKER, date.atTime(18, 0)));
        }
        deviceLogRepository.saveAll(punches);
    }

    // ---- the standing exemption ---------------------------------------------

    @Test
    @DisplayName("an exempt employee who never punches generates as present, not as a month of absence")
    void anExemptEmployeeIsPresentWithoutPunching() {
        generateAttendance(DIRECTOR);

        MonthlyAttendanceResponse month = attendanceService.getMonthlyAttendance(DIRECTOR, PERIOD);

        assertThat(month.presentDays()).isEqualByComparingTo(BigDecimal.valueOf(month.workingDays()));
        assertThat(month.absentDays()).isEqualByComparingTo("0.0");
        assertThat(month.lopDays()).isEqualByComparingTo("0.0");
        assertThat(storedDays(DIRECTOR)).noneMatch(day -> day.getStatus() == AttendanceStatus.ABSENT);
    }

    @Test
    @DisplayName("an exempt employee previews the same roster-only attendance without writing days")
    void anExemptEmployeePreviewIsRosterOnly() {
        MonthlyAttendanceResponse month = attendanceService.getMonthlyAttendance(DIRECTOR, PERIOD);

        assertThat(month.presentDays()).isEqualByComparingTo(BigDecimal.valueOf(month.workingDays()));
        assertThat(month.lopDays()).isEqualByComparingTo("0.0");
        assertThat(month.overtimeHours()).isEqualByComparingTo("0.00");
        assertThat(storedDays(DIRECTOR)).isEmpty();
    }

    @Test
    @DisplayName("without the exemption the same employee would be absent every working day - "
            + "the failure the flag exists to prevent")
    void withoutTheExemptionTheSameEmployeeIsAbsentAllMonth() {
        Employee director = employeeRepository.findByUserId(DIRECTOR).orElseThrow();
        director.setAttendanceExempt(false);
        employeeRepository.save(director);

        generateAttendance(DIRECTOR);

        MonthlyAttendanceResponse month = attendanceService.getMonthlyAttendance(DIRECTOR, PERIOD);
        assertThat(month.presentDays()).isEqualByComparingTo("0.0");
        assertThat(month.lopDays()).isEqualByComparingTo(BigDecimal.valueOf(month.workingDays())
                .setScale(1));
    }

    @Test
    @DisplayName("an exempt employee earns no overtime and no late minutes - there is no clock-in to measure")
    void anExemptEmployeeHasNoOvertimeOrLateness() {
        generateAttendance(DIRECTOR);

        MonthlyAttendanceResponse month = attendanceService.getMonthlyAttendance(DIRECTOR, PERIOD);
        assertThat(month.overtimeHours()).isEqualByComparingTo("0.00");
        assertThat(month.lateCount()).isZero();
        assertThat(month.earlyExitCount()).isZero();
        assertThat(month.invalidPunches()).isZero();
        assertThat(storedDays(DIRECTOR)).allSatisfy(day -> {
            assertThat(day.getFirstIn()).isNull();
            assertThat(day.getLastOut()).isNull();
        });
    }

    @Test
    @DisplayName("week offs, holidays and approved leave still win over the exemption")
    void nonWorkingDaysAndLeaveStillWinForAnExemptEmployee() {
        LocalDate holiday = PERIOD.atDay(2);
        holidayRepository.save(Holiday.builder()
                .holidayName("Founders Day").holidayDate(holiday).optionalHoliday(false).build());
        LocalDate leaveDay = PERIOD.atDay(3);
        approveLeave(DIRECTOR, leaveDay, leaveDay);

        generateAttendance(DIRECTOR);

        assertThat(dayOn(DIRECTOR, holiday).getStatus()).isEqualTo(AttendanceStatus.HOLIDAY);
        assertThat(dayOn(DIRECTOR, leaveDay).getStatus()).isEqualTo(AttendanceStatus.ON_LEAVE);
        LocalDate sunday = firstSunday();
        assertThat(dayOn(DIRECTOR, sunday).getStatus()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
    }

    @Test
    @DisplayName("a punching employee is completely unaffected by the exemption existing")
    void aPunchingEmployeeIsUnaffected() {
        generateAttendance(WORKER);

        MonthlyAttendanceResponse month = attendanceService.getMonthlyAttendance(WORKER, PERIOD);
        assertThat(month.presentDays()).isEqualByComparingTo(BigDecimal.valueOf(month.workingDays()));
        assertThat(storedDays(WORKER)).allSatisfy(day -> {
            if (day.isWorkingDay()) {
                assertThat(day.getFirstIn()).as("punch times are still recorded").isNotNull();
            }
        });
    }

    @Test
    @DisplayName("payroll pays an exempt director the full month")
    void payrollPaysAnExemptDirectorInFull() {
        generateAttendance(DIRECTOR);

        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(DIRECTOR);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        Payroll payroll = payrollService.generate(request);

        assertThat(payroll.getLopDays()).isEqualByComparingTo("0.0");
        assertThat(payroll.getPayableDays())
                .isEqualByComparingTo(BigDecimal.valueOf(PERIOD.lengthOfMonth()).setScale(1));
        assertThat(payroll.getOtAllowance()).isEqualByComparingTo("0.00");
    }

    // ---- the bulk upload ----------------------------------------------------

    @Test
    @DisplayName("one CSV row declares a whole month, skipping week offs rather than restating them")
    void oneRowDeclaresAWholeMonth() {
        AttendanceBulkUploadResponse result = upload(DIRECTOR, PERIOD.atDay(1), PERIOD.atEndOfMonth(),
                AttendanceStatus.PRESENT);

        long sundays = PERIOD.atDay(1).datesUntil(PERIOD.atEndOfMonth().plusDays(1))
                .filter(date -> date.getDayOfWeek() == DayOfWeek.SUNDAY).count();

        assertThat(result.daysApplied()).isEqualTo(PERIOD.lengthOfMonth() - (int) sundays);
        assertThat(result.daysSkipped()).isEqualTo((int) sundays);
        assertThat(result.skipped()).allMatch(day ->
                day.reason() == AttendanceBulkUploadResponse.Reason.NON_WORKING_DAY);
        assertThat(storedDays(DIRECTOR)).allSatisfy(day -> assertThat(day.getRecordStatus())
                .isEqualTo(day.isWorkingDay() ? AttendanceRecordStatus.MANUAL : AttendanceRecordStatus.GENERATED));
    }

    @Test
    @DisplayName("the upload creates the days, so a month never generated can be uploaded directly")
    void theUploadCreatesDaysWithoutGeneratingFirst() {
        assertThat(storedDays(DIRECTOR)).isEmpty();

        upload(DIRECTOR, PERIOD.atDay(1), PERIOD.atEndOfMonth(), AttendanceStatus.PRESENT);

        // The summary payroll consumes exists now, which is what "generate the
        // month first" would otherwise have been required for.
        MonthlyAttendanceResponse month = attendanceService.getMonthlyAttendance(DIRECTOR, PERIOD);
        assertThat(month.presentDays()).isEqualByComparingTo(BigDecimal.valueOf(month.workingDays()));
    }

    @Test
    @DisplayName("approved leave inside an uploaded range is kept and reported, not overwritten")
    void approvedLeaveWinsOverAnUploadedRange() {
        LocalDate leaveFrom = PERIOD.atDay(7);
        LocalDate leaveTo = PERIOD.atDay(8);
        approveLeave(DIRECTOR, leaveFrom, leaveTo);

        AttendanceBulkUploadResponse result =
                upload(DIRECTOR, PERIOD.atDay(1), PERIOD.atEndOfMonth(), AttendanceStatus.PRESENT);

        assertThat(result.skipped())
                .filteredOn(day -> day.reason() == AttendanceBulkUploadResponse.Reason.ON_LEAVE)
                .extracting(AttendanceBulkUploadResponse.SkippedDay::date)
                .containsExactlyInAnyOrder(leaveFrom, leaveTo);
        assertThat(dayOn(DIRECTOR, leaveFrom).getStatus()).isEqualTo(AttendanceStatus.ON_LEAVE);
        assertThat(dayOn(DIRECTOR, leaveFrom).getRecordStatus()).isEqualTo(AttendanceRecordStatus.GENERATED);
    }

    @Test
    @DisplayName("a first upload keeps unpaid leave in the complete month and payroll loss of pay")
    void aFirstUploadDoesNotHideUnpaidLeaveFromPayroll() {
        LocalDate leaveDay = PERIOD.atDay(7);
        approveLeave(DIRECTOR, leaveDay, leaveDay, LeaveType.LEAVE_WITHOUT_PAY);

        AttendanceBulkUploadResponse result = upload(DIRECTOR, PERIOD.atDay(1), PERIOD.atEndOfMonth(),
                AttendanceStatus.PRESENT);

        assertThat(result.skipped()).anyMatch(day -> day.date().equals(leaveDay)
                && day.reason() == AttendanceBulkUploadResponse.Reason.ON_LEAVE);
        assertThat(storedDays(DIRECTOR)).hasSize(PERIOD.lengthOfMonth());
        assertThat(dayOn(DIRECTOR, leaveDay).getStatus()).isEqualTo(AttendanceStatus.ON_LEAVE);
        assertThat(attendanceService.getMonthlyAttendance(DIRECTOR, PERIOD).lopDays())
                .isEqualByComparingTo("1.0");

        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(DIRECTOR);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        Payroll payroll = payrollService.generate(request);

        assertThat(payroll.getLopDays()).isEqualByComparingTo("1.0");
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("29.0");
    }

    @Test
    @DisplayName("an uploaded day overrides the exemption's own generated day and survives regeneration")
    void anUploadedDayOverridesGenerationAndSurvivesIt() {
        generateAttendance(DIRECTOR);
        LocalDate absentDay = PERIOD.atDay(15);

        upload(DIRECTOR, absentDay, absentDay, AttendanceStatus.ABSENT);
        assertThat(dayOn(DIRECTOR, absentDay).getStatus()).isEqualTo(AttendanceStatus.ABSENT);

        generateAttendance(DIRECTOR);

        // MANUAL survives - otherwise the next run would quietly pay the
        // director for a day HR had just recorded them absent for.
        assertThat(dayOn(DIRECTOR, absentDay).getStatus()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(dayOn(DIRECTOR, absentDay).getRecordStatus()).isEqualTo(AttendanceRecordStatus.MANUAL);
        assertThat(dayOn(DIRECTOR, PERIOD.atDay(16)).getStatus()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    @DisplayName("an upload's full-month baseline preserves manual days outside its declared range")
    void anUploadBaselinePreservesOtherManualDays() {
        LocalDate absentDay = PERIOD.atDay(15);
        upload(DIRECTOR, absentDay, absentDay, AttendanceStatus.ABSENT);

        upload(DIRECTOR, PERIOD.atDay(1), PERIOD.atDay(2), AttendanceStatus.PRESENT);

        assertThat(dayOn(DIRECTOR, absentDay).getStatus()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(dayOn(DIRECTOR, absentDay).getRecordStatus()).isEqualTo(AttendanceRecordStatus.MANUAL);
    }

    @Test
    @DisplayName("a locked day is reported as locked rather than silently rewritten")
    void aLockedDayIsReportedNotRewritten() {
        generateAttendance(DIRECTOR);
        attendanceService.lockMonth(DIRECTOR, PERIOD);

        AttendanceBulkUploadResponse result =
                upload(DIRECTOR, PERIOD.atDay(1), PERIOD.atDay(2), AttendanceStatus.ABSENT);

        assertThat(result.daysApplied()).isZero();
        assertThat(result.skipped()).anyMatch(day ->
                day.reason() == AttendanceBulkUploadResponse.Reason.LOCKED);
        assertThat(dayOn(DIRECTOR, PERIOD.atDay(1)).getStatus()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    @DisplayName("a date with no roster row is reported rather than invented")
    void anUnrosteredDayIsReported() {
        LocalDate beyondRoster = PERIOD.plusMonths(1).atDay(1);

        AttendanceBulkUploadResponse result =
                upload(DIRECTOR, beyondRoster, beyondRoster, AttendanceStatus.PRESENT);

        assertThat(result.daysApplied()).isZero();
        assertThat(result.skipped()).singleElement().satisfies(day ->
                assertThat(day.reason()).isEqualTo(AttendanceBulkUploadResponse.Reason.NOT_ROSTERED));
    }

    @Test
    @DisplayName("an uploaded day carries the shift's own hours and no overtime")
    void anUploadedDayCarriesShiftHoursAndNoOvertime() {
        LocalDate date = PERIOD.atDay(1);
        upload(DIRECTOR, date, date, AttendanceStatus.PRESENT);

        DailyAttendance day = dayOn(DIRECTOR, date);
        assertThat(day.getWorkingHours()).isEqualByComparingTo("8.00");
        assertThat(day.getOvertimeHours()).isEqualByComparingTo("0.00");
        assertThat(day.getLateMinutes()).isZero();
        assertThat(day.getEarlyExitMinutes()).isZero();
        assertThat(day.getRemarks()).isNotBlank();
    }

    @Test
    @DisplayName("bulk upload resolves a permanent employee's derived roster without saving shift rows")
    void anUploadUsesTheDerivedRoster() {
        shiftScheduleRepository.deleteAll(shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc(DIRECTOR));
        Shift general = shiftRepository.findAll().get(0);
        general.setShiftCode("GENERAL");
        shiftRepository.save(general);

        AttendanceBulkUploadResponse result = upload(DIRECTOR, PERIOD.atDay(1), PERIOD.atDay(2),
                AttendanceStatus.PRESENT);

        assertThat(result.daysApplied()).isEqualTo(2);
        assertThat(result.daysSkipped()).isZero();
        assertThat(dayOn(DIRECTOR, PERIOD.atDay(1)).getWorkingHours()).isEqualByComparingTo("8.00");
        assertThat(shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc(DIRECTOR)).isEmpty();
    }

    // ---- fixtures -----------------------------------------------------------

    private AttendanceBulkUploadResponse upload(String userId, LocalDate from, LocalDate to,
                                                 AttendanceStatus status) {
        AttendanceBulkUploadRow row = new AttendanceBulkUploadRow();
        row.setUserId(userId);
        row.setFromDate(from);
        row.setToDate(to);
        row.setStatus(status);
        row.setRemarks("Management - exempt from biometric");
        row.setUploadedBy(HR);
        return attendanceService.uploadDays(row);
    }

    private void generateAttendance(String userId) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of(userId));
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    private void approveLeave(String userId, LocalDate from, LocalDate to) {
        approveLeave(userId, from, to, LeaveType.CASUAL_LEAVE);
    }

    private void approveLeave(String userId, LocalDate from, LocalDate to, LeaveType type) {
        LeaveRequestPayload payload = new LeaveRequestPayload();
        payload.setUserId(userId);
        payload.setLeaveType(type);
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

    private List<DailyAttendance> storedDays(String userId) {
        return dailyAttendanceRepository.findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                userId, PERIOD.atDay(1), PERIOD.atEndOfMonth());
    }

    private DailyAttendance dayOn(String userId, LocalDate date) {
        return dailyAttendanceRepository.findByUserIdAndAttendanceDate(userId, date).orElseThrow();
    }

    private LocalDate firstSunday() {
        return PERIOD.atDay(1).datesUntil(PERIOD.atEndOfMonth().plusDays(1))
                .filter(date -> date.getDayOfWeek() == DayOfWeek.SUNDAY)
                .findFirst().orElseThrow();
    }

    private void saveEmployee(String userId, String code, String name, Role role, boolean exempt) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode(code).employeeName(name)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("80000")).pfBasic(new BigDecimal("15000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(true)
                .attendanceExempt(exempt)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }

    private DeviceLog punch(String userId, java.time.LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(userId).logDate(at).build();
    }
}
