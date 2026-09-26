package com.accusharp.hrms.weekoff;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.DailyAttendance;
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
import com.accusharp.hrms.service.report.ReportService;
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
 * Day-wise staff, and the weekly off HR records for them.
 *
 * <p>Day-wise workers are rostered by hand and paid for the days they attend.
 * The rules HR asked for:
 *
 * <ul>
 *   <li>a working day with no shift assigned is {@code ABSENT};</li>
 *   <li>a day with a shift and punches is {@code PRESENT};</li>
 *   <li>their configured weekly off, with no shift assigned, is
 *       {@code WEEKLY_OFF} rather than an absence;</li>
 *   <li>and anyone working their weekly off is tracked, including someone who
 *       punched in on it with no shift assigned - that is either a roster
 *       somebody forgot to write or a day worked unpaid, and HR needs to see
 *       both.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> a default: a day-wise employee with nothing
 * configured behaves exactly as before. The deleted cron never rostered them,
 * so there is no existing behaviour to preserve and no reason to invent a day
 * off nobody agreed to.
 *
 * <p>September 2026 starts on a Tuesday; its Sundays are the 6th, 13th, 20th
 * and 27th.
 */
@SpringBootTest
class DayWiseWeekOffTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final String HR = "HR960";
    private static final String WORKER = "DW960";

    private static final LocalDate FIRST_TUESDAY = LocalDate.of(2026, 9, 1);
    private static final LocalDate FIRST_SUNDAY = LocalDate.of(2026, 9, 6);

    @Autowired private AttendanceService attendanceService;
    @Autowired private ReportService reportService;
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
    private long punchId = 96_000;

    @BeforeEach
    void setUp() {
        clean();
        punchId = 96_000;

        company = companyRepository.save(Company.builder()
                .companyCode("DAYWISE-CO").companyName("Day Wise Co").status(RecordStatus.ACTIVE).build());
        general = shiftRepository.save(Shift.builder()
                .company(company).shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240)
                .build());

        saveEmployee(HR, Role.HR, EmployeeStatus.PERMANENT, null);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- the four rules ----------------------------------------------------

    @Test
    @DisplayName("their weekly off with no shift assigned is WEEKLY_OFF; any other unassigned day is ABSENT")
    void unassignedWeeklyOffIsWeeklyOffNotAbsent() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));

        generate(WORKER);

        Map<LocalDate, AttendanceRecordResponse> byDate = recordsByDate(WORKER);
        assertThat(byDate.get(FIRST_SUNDAY).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        assertThat(byDate.get(FIRST_SUNDAY).weekOff()).isTrue();
        assertThat(byDate.get(FIRST_TUESDAY).status()).isEqualTo(AttendanceStatus.ABSENT);

        MonthlyAttendanceSummary summary = summary(WORKER);
        assertThat(summary.getWeekOffDays()).isEqualTo(4);
        assertThat(summary.getWorkingDays()).isEqualTo(26);
    }

    @Test
    @DisplayName("a working day with a shift assigned and punches is PRESENT")
    void assignedDayWithPunchesIsPresent() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));
        roster(WORKER, FIRST_TUESDAY, false);
        workedFullDay(WORKER, FIRST_TUESDAY);

        generate(WORKER);

        assertThat(recordsByDate(WORKER).get(FIRST_TUESDAY).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(summary(WORKER).getPresentDays()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("a day-wise employee with no weekly off configured is exactly as before - Sunday is absent")
    void unconfiguredDayWiseEmployeeIsUnchanged() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, null);

        generate(WORKER);

        AttendanceRecordResponse sunday = recordsByDate(WORKER).get(FIRST_SUNDAY);
        assertThat(sunday.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(sunday.weekOff()).isFalse();
        assertThat(summary(WORKER).getWeekOffDays()).isZero();
        assertThat(summary(WORKER).getWorkingDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("a contract employee HR has given a weekly off gets it honoured the same way")
    void configuredContractEmployeeIsHonouredToo() {
        // Only once somebody sets it. An unconfigured contract employee is
        // covered by UnrosteredAttendanceTest and stays all-absent.
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.CONTRACT, Set.of(DayOfWeek.SUNDAY));

        generate(WORKER);

        assertThat(recordsByDate(WORKER).get(FIRST_SUNDAY).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
    }

    // ---- working the weekly off --------------------------------------------

    @Test
    @DisplayName("punching on the weekly off with no shift assigned stays WEEKLY_OFF, but is counted")
    void unassignedPunchOnWeeklyOffIsTracked() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));
        workedFullDay(WORKER, FIRST_SUNDAY);

        generate(WORKER);

        AttendanceRecordResponse sunday = recordsByDate(WORKER).get(FIRST_SUNDAY);
        // No shift, so not present - the rule HR set.
        assertThat(sunday.status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
        assertThat(sunday.shiftCode()).isNull();
        // But the evidence is kept, and the day is counted rather than buried.
        assertThat(sunday.firstIn()).isEqualTo(FIRST_SUNDAY.atTime(9, 0));

        MonthlyAttendanceSummary summary = summary(WORKER);
        assertThat(summary.getWeekOffUnrosteredPunchDays()).isEqualTo(1);
        assertThat(summary.getWeekOffWorkedDays()).isZero();
        assertThat(summary.getPresentDays()).isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("working the weekly off on an assigned shift is PRESENT, paid, and counted as a worked weekly off")
    void assignedWeeklyOffWorkedIsPresentAndTracked() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));
        // HR put them on for the day - an ordinary working roster row.
        roster(WORKER, FIRST_SUNDAY, false);
        workedFullDay(WORKER, FIRST_SUNDAY);

        generate(WORKER);

        assertThat(recordsByDate(WORKER).get(FIRST_SUNDAY).status()).isEqualTo(AttendanceStatus.PRESENT);

        DailyAttendance stored = storedDay(WORKER, FIRST_SUNDAY);
        // The roster made it a working day...
        assertThat(stored.isWeekOff()).isFalse();
        // ...but it was still their weekly off, and that is what gets tracked.
        assertThat(stored.isConfiguredWeekOff()).isTrue();

        MonthlyAttendanceSummary summary = summary(WORKER);
        assertThat(summary.getWeekOffWorkedDays()).isEqualTo(1);
        assertThat(summary.getPresentDays()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("a permanent employee working their derived weekly off is counted too")
    void permanentEmployeeWorkingTheirWeeklyOffIsTracked() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.PERMANENT, Set.of(DayOfWeek.SUNDAY));
        workedFullDay(WORKER, FIRST_SUNDAY);

        generate(WORKER);

        assertThat(recordsByDate(WORKER).get(FIRST_SUNDAY).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(summary(WORKER).getWeekOffWorkedDays()).isEqualTo(1);
        // It had a shift - the derived GENERAL day - so it is not "unassigned".
        assertThat(summary(WORKER).getWeekOffUnrosteredPunchDays()).isZero();
    }

    @Test
    @DisplayName("the count is snapshotted at generation, so changing the weekly off later cannot rewrite it")
    void trackingIsSnapshottedAtGeneration() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));
        roster(WORKER, FIRST_SUNDAY, false);
        workedFullDay(WORKER, FIRST_SUNDAY);
        generate(WORKER);

        Employee worker = employeeRepository.findByUserId(WORKER).orElseThrow();
        worker.setWeekOffDays(Set.of(DayOfWeek.TUESDAY));
        employeeRepository.saveAndFlush(worker);

        // Every exception report resyncs summaries from the stored days before
        // it reads them. If the count were re-derived from the employee record
        // here, changing someone's weekly off in October would silently rewrite
        // September's report.
        attendanceService.syncSummaries(PERIOD);

        assertThat(summary(WORKER).getWeekOffWorkedDays()).isEqualTo(1);
    }

    // ---- the report --------------------------------------------------------

    @Test
    @DisplayName("the weekly-off report lists who worked theirs, and who punched in on it with no shift")
    void weekOffWorkedReportListsBoth() {
        saveEmployee("DW961", Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));
        roster("DW961", FIRST_SUNDAY, false);
        workedFullDay("DW961", FIRST_SUNDAY);

        saveEmployee("DW962", Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));
        workedFullDay("DW962", FIRST_SUNDAY);

        saveEmployee("DW963", Role.EMPLOYEE, EmployeeStatus.DAY_WISE, Set.of(DayOfWeek.SUNDAY));

        generateFor(List.of("DW961", "DW962", "DW963"));

        Map<String, String> detailByUser = reportService.weekOffWorkedReport(PERIOD).stream()
                .collect(Collectors.toMap(ReportDtos.ExceptionRow::userId, ReportDtos.ExceptionRow::detail));

        assertThat(detailByUser).containsOnlyKeys("DW961", "DW962");
        assertThat(detailByUser.get("DW961")).contains("1 day(s) worked on a weekly off");
        assertThat(detailByUser.get("DW962")).contains("1 day(s) punched on a weekly off with no shift assigned");
    }

    // ---- fixtures ----------------------------------------------------------

    private void generate(String userId) {
        generateFor(List.of(userId));
    }

    private void generateFor(List<String> userIds) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(userIds);
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    private Map<LocalDate, AttendanceRecordResponse> recordsByDate(String userId) {
        return attendanceService.getRecords(userId, PERIOD).stream()
                .collect(Collectors.toMap(AttendanceRecordResponse::attendanceDate, r -> r,
                        (a, b) -> a, TreeMap::new));
    }

    private DailyAttendance storedDay(String userId, LocalDate date) {
        return dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(userId, date, date)
                .getFirst();
    }

    private MonthlyAttendanceSummary summary(String userId) {
        return summaryRepository.findByUserIdAndMonth(userId, PERIOD.toString()).orElseThrow();
    }

    private void roster(String userId, LocalDate date, boolean weekOff) {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(userId).shiftDate(date).shift(general).weekOff(weekOff).assignedBy(HR).build());
    }

    private void workedFullDay(String userId, LocalDate date) {
        punch(userId, date.atTime(9, 0));
        punch(userId, date.atTime(18, 0));
    }

    private void punch(String userId, LocalDateTime at) {
        deviceLogRepository.save(DeviceLog.builder()
                .deviceLogId(punchId++).deviceId(96L).userId(userId).logDate(at).build());
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
