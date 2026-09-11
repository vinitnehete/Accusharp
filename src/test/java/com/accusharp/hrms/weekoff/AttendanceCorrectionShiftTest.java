package com.accusharp.hrms.weekoff;

import com.accusharp.hrms.dto.AttendanceCorrectionRequest;
import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.attendance.AttendanceService;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HR correcting a day's times, when the day's shift is not a stored roster row.
 *
 * <p>The bug: HR entered a permanent employee's real in and out times and the
 * day stayed {@code ABSENT}. {@code correctDay} looked the shift up straight
 * from the roster table - and permanent staff no longer have roster rows, their
 * {@code GENERAL} days are derived (see {@code DefaultRosterResolver}). With no
 * shift found it refused the times outright, and forcing {@code PRESENT} to get
 * past that saved the day with zero hours, because the hours come from the same
 * missing shift.
 *
 * <p>Two fixes, pinned here:
 * <ul>
 *   <li>a correction resolves the shift the same way generation does, so a
 *       derived day corrects exactly like a stored one;</li>
 *   <li>a day with no shift at all can be given one in the correction itself.
 *       It is saved as a real roster row, so the planner shows it and a later
 *       regeneration computes against it.</li>
 * </ul>
 *
 * <p>September 2026 starts on a Tuesday; the first Sunday is the 6th.
 */
@SpringBootTest
class AttendanceCorrectionShiftTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final String HR = "HR980";
    private static final String EMPLOYEE = "MC980";

    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 1);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 6);

    @Autowired private AttendanceService attendanceService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository summaryRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;

    private Company company;
    private Shift night;
    private long punchId = 98_000;

    @BeforeEach
    void setUp() {
        clean();
        punchId = 98_000;

        company = companyRepository.save(Company.builder()
                .companyCode("CORRECT-CO").companyName("Correction Co").status(RecordStatus.ACTIVE).build());
        shiftRepository.save(Shift.builder()
                .company(company).shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240)
                .build());
        night = shiftRepository.save(Shift.builder()
                .company(company).shiftCode("NIGHT").shiftName("Night")
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(8, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(120)
                .build());

        saveEmployee(HR, Role.HR, EmployeeStatus.PERMANENT, null);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- the bug -----------------------------------------------------------

    @Test
    @DisplayName("correcting a permanent employee's times on a derived day makes it PRESENT, with real hours")
    void derivedDayCorrectsLikeAStoredOne() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, EmployeeStatus.PERMANENT, Set.of(DayOfWeek.SUNDAY));
        generate(EMPLOYEE);
        assertThat(recordOn(EMPLOYEE, TUESDAY).status()).isEqualTo(AttendanceStatus.ABSENT);

        AttendanceRecordResponse corrected = correct(EMPLOYEE, TUESDAY,
                TUESDAY.atTime(9, 0), TUESDAY.atTime(18, 0), null, null);

        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(corrected.shiftCode()).isEqualTo("GENERAL");
        // 09:00-18:00 less the shift's own 60-minute break.
        assertThat(corrected.workingHours()).isEqualByComparingTo("8.00");
        assertThat(corrected.recordStatus()).isEqualTo(AttendanceRecordStatus.MANUAL);
        // Fixing the times is not a roster change - the day stays derived.
        assertThat(shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc(EMPLOYEE)).isEmpty();
    }

    @Test
    @DisplayName("forcing PRESENT on a derived day carries the shift's hours, not zero")
    void forcedPresentOnADerivedDayHasTheShiftsHours() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, EmployeeStatus.PERMANENT, Set.of(DayOfWeek.SUNDAY));
        generate(EMPLOYEE);

        AttendanceRecordResponse corrected = correct(EMPLOYEE, TUESDAY, null, null, null,
                AttendanceStatus.PRESENT);

        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(corrected.workingHours()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("a day generated before the fix - blank, no shift - is correctable against the derived shift")
    void aStaleBlankDayFromBeforeTheFixIsCorrectable() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, EmployeeStatus.PERMANENT, Set.of(DayOfWeek.SUNDAY));
        // What every permanent employee's month past the old cron's horizon looks
        // like in a client database right now: written blank and ABSENT, because
        // there was no roster row when it was generated.
        dailyAttendanceRepository.save(DailyAttendance.builder()
                .userId(EMPLOYEE).attendanceDate(TUESDAY).shiftCode(null)
                .workingHours(BigDecimal.ZERO).breakHours(BigDecimal.ZERO).overtimeHours(BigDecimal.ZERO)
                .lateMinutes(0).earlyExitMinutes(0).invalidPunch(false).weekOff(false).holiday(false)
                .status(AttendanceStatus.ABSENT).recordStatus(AttendanceRecordStatus.GENERATED)
                .locked(false).generatedAt(Instant.now()).generatedBy(HR)
                .build());

        AttendanceRecordResponse corrected = correct(EMPLOYEE, TUESDAY,
                TUESDAY.atTime(9, 0), TUESDAY.atTime(18, 0), null, null);

        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(corrected.shiftCode()).isEqualTo("GENERAL");
    }

    // ---- a day with no shift at all ----------------------------------------

    @Test
    @DisplayName("a day nobody rostered can be given its shift in the correction, and it becomes a roster row")
    void anUnrosteredDayCanBeGivenAShift() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);
        generate(EMPLOYEE);
        assertThat(recordOn(EMPLOYEE, TUESDAY).shiftCode()).isNull();

        AttendanceRecordResponse corrected = correct(EMPLOYEE, TUESDAY,
                TUESDAY.atTime(9, 0), TUESDAY.atTime(18, 0), "GENERAL", null);

        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(corrected.shiftCode()).isEqualTo("GENERAL");
        assertThat(corrected.workingHours()).isEqualByComparingTo("8.00");

        // Stored for real: the planner shows it, and a later regeneration
        // computes against it instead of writing the day back to ABSENT.
        ShiftSchedule assigned = shiftScheduleRepository.findByUserIdAndShiftDate(EMPLOYEE, TUESDAY).orElseThrow();
        assertThat(assigned.getShift().getShiftCode()).isEqualTo("GENERAL");
        assertThat(assigned.isWeekOff()).isFalse();
        assertThat(assigned.getAssignedBy()).isEqualTo(HR);
    }

    @Test
    @DisplayName("times on a day with no shift, and no shift chosen, still says what is missing")
    void noShiftAndNoneChosenIsRefused() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);
        generate(EMPLOYEE);

        assertThatThrownBy(() -> correct(EMPLOYEE, TUESDAY,
                TUESDAY.atTime(9, 0), TUESDAY.atTime(18, 0), null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No shift is rostered")
                .hasMessageContaining("choose the shift");
    }

    @Test
    @DisplayName("a derived day can be switched to a different shift from the correction, which stores it")
    void aDerivedShiftCanBeOverriddenForTheDay() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, EmployeeStatus.PERMANENT, Set.of(DayOfWeek.SUNDAY));
        generate(EMPLOYEE);

        // The GENERAL day was only ever a default - HR saying they actually
        // worked the night is exactly the explicit assignment that overrides it.
        AttendanceRecordResponse corrected = correct(EMPLOYEE, TUESDAY,
                TUESDAY.atTime(18, 0), TUESDAY.plusDays(1).atTime(8, 0), "NIGHT", null);

        assertThat(corrected.shiftCode()).isEqualTo("NIGHT");
        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(shiftScheduleRepository.findByUserIdAndShiftDate(EMPLOYEE, TUESDAY).orElseThrow()
                .getShift().getShiftCode()).isEqualTo("NIGHT");
    }

    @Test
    @DisplayName("a shift somebody actually rostered cannot be swapped from the correction screen")
    void aStoredShiftIsNotOverwrittenByACorrection() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(TUESDAY).shift(night).weekOff(false).assignedBy(HR).build());
        generate(EMPLOYEE);

        // Changing a real roster assignment belongs on the roster screen, with
        // its own audit trail and rest-gap warnings - not as a side effect of
        // fixing a punch time.
        assertThatThrownBy(() -> correct(EMPLOYEE, TUESDAY,
                TUESDAY.atTime(9, 0), TUESDAY.atTime(18, 0), "GENERAL", null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("NIGHT")
                .hasMessageContaining("change it on the roster");
    }

    // ---- ties into the weekly-off tracking ---------------------------------

    @Test
    @DisplayName("a weekly off punched with no shift can be corrected into a worked, tracked day")
    void unrosteredWeeklyOffPunchCorrectsIntoAWorkedDay() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));
        punch(EMPLOYEE, SUNDAY.atTime(9, 0));
        punch(EMPLOYEE, SUNDAY.atTime(18, 0));
        generate(EMPLOYEE);
        assertThat(recordOn(EMPLOYEE, SUNDAY).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        assertThat(summary(EMPLOYEE).getWeekOffUnrosteredPunchDays()).isEqualTo(1);

        // The report flagged it; HR confirms they were meant to be in.
        AttendanceRecordResponse corrected = correct(EMPLOYEE, SUNDAY,
                SUNDAY.atTime(9, 0), SUNDAY.atTime(18, 0), "GENERAL", null);

        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(corrected.configuredWeekOff()).isTrue();

        MonthlyAttendanceSummary summary = summary(EMPLOYEE);
        assertThat(summary.getWeekOffWorkedDays()).isEqualTo(1);
        assertThat(summary.getWeekOffUnrosteredPunchDays()).isZero();
        assertThat(summary.getPresentDays()).isEqualByComparingTo("1.0");
    }

    // ---- fixtures ----------------------------------------------------------

    private AttendanceRecordResponse correct(String userId, LocalDate date, LocalDateTime firstIn,
                                             LocalDateTime lastOut, String shiftCode, AttendanceStatus status) {
        AttendanceCorrectionRequest request = new AttendanceCorrectionRequest();
        request.setFirstIn(firstIn);
        request.setLastOut(lastOut);
        request.setShiftCode(shiftCode);
        request.setStatus(status);
        request.setRemarks("device missed the punches");
        request.setUpdatedBy(HR);
        return attendanceService.correctDay(userId, date, request);
    }

    private void generate(String userId) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of(userId));
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    private AttendanceRecordResponse recordOn(String userId, LocalDate date) {
        return attendanceService.getRecords(userId, PERIOD).stream()
                .filter(record -> record.attendanceDate().equals(date))
                .findFirst().orElseThrow();
    }

    private MonthlyAttendanceSummary summary(String userId) {
        return summaryRepository.findByUserIdAndMonth(userId, PERIOD.toString()).orElseThrow();
    }

    private void punch(String userId, LocalDateTime at) {
        deviceLogRepository.save(DeviceLog.builder()
                .deviceLogId(punchId++).deviceId(98L).userId(userId).logDate(at).build());
    }

    private Employee saveEmployee(String userId, Role role, EmployeeStatus status, Set<DayOfWeek> weekOffDays) {
        return employeeRepository.saveAndFlush(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(status).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .weekOffDays(weekOffDays)
                .overtimeEligible(false)
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }

    private void clean() {
        dailyAttendanceRepository.deleteAll();
        summaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        holidayRepository.deleteAll();
        companyRepository.deleteAll();
    }
}
