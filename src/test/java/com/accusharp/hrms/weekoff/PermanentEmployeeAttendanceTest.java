package com.accusharp.hrms.weekoff;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bug this whole change exists for: a permanent employee who came to work
 * every day, and whose month came back entirely {@code ABSENT}.
 *
 * <p>Permanent staff always work {@code GENERAL}, so nobody rosters them by
 * hand. A cron job used to write their roster rows two months ahead; past that
 * horizon they had none, and attendance generation with no shift to measure
 * against wrote every day down as {@code ABSENT} - with the employee's real
 * punch times sitting in the same row, contradicting the status it had just
 * been given. Every one of those days was loss of pay for somebody who had
 * turned up.
 *
 * <p>September 2026: 30 days. It starts on a Tuesday, so the month holds four
 * Sundays (6th, 13th, 20th, 27th) and five Tuesdays.
 */
@SpringBootTest
class PermanentEmployeeAttendanceTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final String HR = "HR950";
    private static final String EMPLOYEE = "PE950";

    private static final LocalDate FIRST_SUNDAY = LocalDate.of(2026, 9, 6);
    private static final LocalDate FIRST_TUESDAY = LocalDate.of(2026, 9, 1);

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
    private Shift general;
    private Shift night;
    private long punchId = 95_000;

    @BeforeEach
    void setUp() {
        clean();
        punchId = 95_000;

        company = companyRepository.save(Company.builder()
                .companyCode("PERM-CO").companyName("Permanent Co").status(RecordStatus.ACTIVE).build());

        general = shiftRepository.save(Shift.builder()
                .company(company).shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240)
                .build());
        night = shiftRepository.save(Shift.builder()
                .company(company).shiftCode("NIGHT").shiftName("Night")
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(8, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(120)
                .build());

        saveEmployee(HR, Role.HR, null);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- the bug -----------------------------------------------------------

    @Test
    @DisplayName("a permanent employee who punched is PRESENT, with no roster row anywhere")
    void permanentEmployeeWhoPunchedIsPresent() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));
        workedFullDayOn(FIRST_TUESDAY);

        generate();

        AttendanceRecordResponse day = recordsByDate().get(FIRST_TUESDAY);
        assertThat(day.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(day.shiftCode()).isEqualTo("GENERAL");
        assertThat(day.firstIn()).isEqualTo(FIRST_TUESDAY.atTime(9, 0));
        assertThat(day.lastOut()).isEqualTo(FIRST_TUESDAY.atTime(18, 0));
        // 09:00-18:00 less the shift's own 60-minute break.
        assertThat(day.workingHours()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("the whole month is generated without a single roster row being written")
    void wholeMonthIsGeneratedAndNothingIsStored() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));

        AttendanceGenerationResponse response = generate();

        assertThat(response.daysGenerated()).isEqualTo(30);
        // Not "unrostered": these days have a shift, they were simply never
        // written down. That distinction is the whole fix.
        assertThat(response.unrosteredDaysGenerated()).isZero();
        assertThat(response.employeesWithoutRoster()).isEmpty();

        // The derived days are transient. Nothing reached the roster table.
        assertThat(shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc(EMPLOYEE)).isEmpty();
    }

    @Test
    @DisplayName("a day they did not come in is still ABSENT")
    void noPunchIsStillAbsent() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));
        workedFullDayOn(FIRST_TUESDAY);

        generate();

        Map<LocalDate, AttendanceRecordResponse> byDate = recordsByDate();
        assertThat(byDate.get(FIRST_TUESDAY).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(byDate.get(PERIOD.atDay(2)).status()).isEqualTo(AttendanceStatus.ABSENT);
        // Absent against a real shift now, so the day says which shift was missed.
        assertThat(byDate.get(PERIOD.atDay(2)).shiftCode()).isEqualTo("GENERAL");
    }

    // ---- the weekly off ----------------------------------------------------

    @Test
    @DisplayName("their weekly off is WEEKLY_OFF, not ABSENT")
    void weeklyOffIsNotAbsent() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));

        generate();

        Map<LocalDate, AttendanceRecordResponse> byDate = recordsByDate();
        assertThat(byDate.get(FIRST_SUNDAY).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        assertThat(byDate.get(FIRST_SUNDAY).weekOff()).isTrue();
        assertThat(byDate.get(FIRST_TUESDAY).weekOff()).isFalse();
    }

    @Test
    @DisplayName("two employees on the same day get their own weekly off, not a shared one")
    void eachEmployeeGetsTheirOwnWeeklyOff() {
        saveEmployee("PE951", Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));
        saveEmployee("PE952", Role.EMPLOYEE, Set.of(DayOfWeek.TUESDAY));

        generateFor(List.of("PE951", "PE952"));

        // This is the case the old hardcoded Set.of(SUNDAY) could not express
        // at all: everyone got Sunday whether it was their day or not.
        assertThat(statusOn("PE951", FIRST_SUNDAY)).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        assertThat(statusOn("PE951", FIRST_TUESDAY)).isEqualTo(AttendanceStatus.ABSENT);

        assertThat(statusOn("PE952", FIRST_TUESDAY)).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        assertThat(statusOn("PE952", FIRST_SUNDAY)).isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    @DisplayName("an employee with a two-day weekend is off on both days")
    void twoDayWeekend() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));

        generate();

        Map<LocalDate, AttendanceRecordResponse> byDate = recordsByDate();
        assertThat(byDate.get(LocalDate.of(2026, 9, 5)).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        assertThat(byDate.get(LocalDate.of(2026, 9, 6)).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        // Four Saturdays (5th, 12th, 19th, 26th) and four Sundays (6th, 13th, 20th, 27th).
        assertThat(summary(EMPLOYEE).getWeekOffDays()).isEqualTo(8);
    }

    @Test
    @DisplayName("coming in on their weekly off is PRESENT, not an error")
    void workingOnTheirWeeklyOffIsPresent() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));
        workedFullDayOn(FIRST_SUNDAY);

        generate();

        AttendanceRecordResponse day = recordsByDate().get(FIRST_SUNDAY);
        assertThat(day.status()).isEqualTo(AttendanceStatus.PRESENT);
        // Still a week off - the flag records what the day was, the status
        // records what happened on it.
        assertThat(day.weekOff()).isTrue();
    }

    @Test
    @DisplayName("an employee with no weekly off configured still gets Sunday, as before")
    void unconfiguredEmployeeKeepsSunday() {
        // Every employee row in an existing database has this column null. The
        // month they get on Monday has to be the month they got on Friday.
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, null);

        generate();

        assertThat(recordsByDate().get(FIRST_SUNDAY).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        assertThat(summary(EMPLOYEE).getWeekOffDays()).isEqualTo(4);
    }

    // ---- the month's arithmetic --------------------------------------------

    @Test
    @DisplayName("the summary counts week offs out of working days rather than into loss of pay")
    void summaryCountsWeekOffsCorrectly() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));
        // Work every day that is not a Sunday.
        PERIOD.atDay(1).datesUntil(PERIOD.atEndOfMonth().plusDays(1))
                .filter(date -> date.getDayOfWeek() != DayOfWeek.SUNDAY)
                .forEach(this::workedFullDayOn);

        generate();

        MonthlyAttendanceSummary summary = summary(EMPLOYEE);
        assertThat(summary.getWeekOffDays()).isEqualTo(4);
        assertThat(summary.getWorkingDays()).isEqualTo(26);
        assertThat(summary.getPresentDays()).isEqualByComparingTo("26.0");
        // The whole point: somebody who came in every working day owes nothing.
        assertThat(summary.getLopDays()).isEqualByComparingTo("0.0");
    }

    // ---- explicit rows still win -------------------------------------------

    @Test
    @DisplayName("HR can still put a permanent employee on a night shift for one day")
    void anExplicitRosterRowStillWins() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));
        LocalDate nightDate = PERIOD.atDay(9);
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(nightDate).shift(night)
                .weekOff(false).assignedBy(HR).build());
        punch(nightDate.atTime(18, 0));
        punch(nightDate.plusDays(1).atTime(8, 0));

        generate();

        Map<LocalDate, AttendanceRecordResponse> byDate = recordsByDate();
        assertThat(byDate.get(nightDate).shiftCode()).isEqualTo("NIGHT");
        assertThat(byDate.get(nightDate).status()).isEqualTo(AttendanceStatus.PRESENT);
        // Its neighbours fall back to the default, and the night shift's exit
        // punch the next morning does not become the next day's entry.
        assertThat(byDate.get(nightDate.plusDays(1)).shiftCode()).isEqualTo("GENERAL");
        assertThat(byDate.get(nightDate.plusDays(1)).status()).isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    @DisplayName("a defaulted week off can be overridden into a working day for one Sunday")
    void hrCanOverrideOneWeekOff() {
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(FIRST_SUNDAY).shift(general)
                .weekOff(false).assignedBy(HR).build());

        generate();

        Map<LocalDate, AttendanceRecordResponse> byDate = recordsByDate();
        assertThat(byDate.get(FIRST_SUNDAY).weekOff()).isFalse();
        assertThat(byDate.get(FIRST_SUNDAY).status()).isEqualTo(AttendanceStatus.ABSENT);
        // The other Sundays are untouched.
        assertThat(byDate.get(FIRST_SUNDAY.plusDays(7)).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
    }

    // ---- who this does not apply to ----------------------------------------

    @Test
    @DisplayName("a contract employee is still left to an explicit roster")
    void contractEmployeeIsNotDefaulted() {
        Employee contract = saveEmployee("PE953", Role.EMPLOYEE, Set.of(DayOfWeek.SUNDAY));
        contract.setStatus(EmployeeStatus.CONTRACT);
        employeeRepository.saveAndFlush(contract);

        AttendanceGenerationResponse response = generateFor(List.of("PE953"));

        // Rostered sparsely on purpose - filling their days would invent LOP.
        assertThat(response.unrosteredDaysGenerated()).isEqualTo(30);
        assertThat(response.employeesWithoutRoster()).containsExactly("PE953");
    }

    // ---- fixtures ----------------------------------------------------------

    private AttendanceGenerationResponse generate() {
        return generateFor(List.of(EMPLOYEE));
    }

    private AttendanceGenerationResponse generateFor(List<String> userIds) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(userIds);
        request.setGeneratedBy(HR);
        return attendanceService.generate(request);
    }

    private Map<LocalDate, AttendanceRecordResponse> recordsByDate() {
        return recordsByDate(EMPLOYEE);
    }

    private Map<LocalDate, AttendanceRecordResponse> recordsByDate(String userId) {
        return attendanceService.getRecords(userId, PERIOD).stream()
                .collect(Collectors.toMap(AttendanceRecordResponse::attendanceDate, r -> r,
                        (a, b) -> a, TreeMap::new));
    }

    private AttendanceStatus statusOn(String userId, LocalDate date) {
        return recordsByDate(userId).get(date).status();
    }

    private MonthlyAttendanceSummary summary(String userId) {
        return summaryRepository.findByUserIdAndMonth(userId, PERIOD.toString()).orElseThrow();
    }

    private void workedFullDayOn(LocalDate date) {
        punch(date.atTime(9, 0));
        punch(date.atTime(18, 0));
    }

    private void punch(LocalDateTime at) {
        deviceLogRepository.save(DeviceLog.builder()
                .deviceLogId(punchId++).deviceId(95L).userId(EMPLOYEE).logDate(at).build());
    }

    private Employee saveEmployee(String userId, Role role, Set<DayOfWeek> weekOffDays) {
        return employeeRepository.saveAndFlush(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
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
