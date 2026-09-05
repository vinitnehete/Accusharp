package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceCorrectionRequest;
import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Holiday;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attendance for the days nobody rostered.
 *
 * <p>The bug this covers: an employee HR forgot to schedule generated nothing
 * at all. The month came back blank - no rows to review, nothing to correct -
 * and payroll, which derives loss of pay from {@code workingDays - presentDays
 * - paidLeave}, saw zero working days and paid the month in full. Silence read
 * as "no attendance was expected" when it actually meant "nobody said what was
 * expected".
 *
 * <p>Those days are now written blank and {@code ABSENT}: visible, reviewable,
 * correctable. What the suite pins down is the set of days that must
 * <em>not</em> be swept up with them - a mandatory holiday, an approved leave,
 * anything outside the employee's own joining and relieving dates - because
 * each of those would be loss of pay invented out of a missing roster row.
 *
 * <p>September 2026: 30 days, GENERAL 09:00-18:00, 8 paid hours.
 */
@SpringBootTest
class UnrosteredAttendanceTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final String EMPLOYEE = "SE900";
    private static final String HR = "HR900";

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
    private Shift shift;
    private long punchId = 90_000;

    @BeforeEach
    void setUp() {
        clean();
        punchId = 90_000;

        company = companyRepository.save(Company.builder()
                .companyCode("GAP-CO").companyName("Gap Co").status(RecordStatus.ACTIVE).build());

        shift = shiftRepository.save(Shift.builder()
                .company(company).shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240)
                .build());

        saveEmployee(HR, Role.HR, LocalDate.of(2022, 1, 1), null);
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, LocalDate.of(2022, 1, 1), null);
    }

    /** This class owns a company of its own, so it clears up after itself. */
    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- the bug -----------------------------------------------------------

    @Test
    @DisplayName("an employee nobody rostered still gets a full month of reviewable days")
    void anUnrosteredEmployeeStillGetsAMonth() {
        AttendanceGenerationResponse response = generate(true);

        assertThat(response.daysGenerated()).isEqualTo(30);
        assertThat(response.unrosteredDaysGenerated()).isEqualTo(30);
        // Still reported: the point is that the roster is missing, and filling
        // the days in must not hide that from whoever ran the generation.
        assertThat(response.employeesWithoutRoster()).containsExactly(EMPLOYEE);

        List<AttendanceRecordResponse> records = attendanceService.getRecords(EMPLOYEE, PERIOD);
        assertThat(records).hasSize(30);
        assertThat(records).allMatch(r -> r.status() == AttendanceStatus.ABSENT);
        assertThat(records).allMatch(r -> r.recordStatus() == AttendanceRecordStatus.GENERATED);

        AttendanceRecordResponse day = records.getFirst();
        assertThat(day.shiftCode()).isNull();
        assertThat(day.firstIn()).isNull();
        assertThat(day.lastOut()).isNull();
        assertThat(day.workingHours()).isEqualByComparingTo("0.00");
        assertThat(day.overtimeHours()).isEqualByComparingTo("0.00");
        assertThat(day.lateMinutes()).isZero();
        assertThat(day.earlyExitMinutes()).isZero();
        // The device is fine - it is the roster that is missing. Flagging this
        // as an invalid punch would send HR to fix a reader that works.
        assertThat(day.invalidPunch()).isFalse();
        assertThat(day.weekOff()).isFalse();

        MonthlyAttendanceSummary summary = summary();
        assertThat(summary.getWorkingDays()).isEqualTo(30);
        assertThat(summary.getPresentDays()).isEqualByComparingTo("0.0");
        assertThat(summary.getLopDays()).isEqualByComparingTo("30.0");
    }

    @Test
    @DisplayName("a gap inside a partial roster is filled without touching the rostered days")
    void gapsAreFilledAndRosteredDaysAreUntouched() {
        rosterAndPunch(PERIOD.atDay(1));
        rosterAndPunch(PERIOD.atDay(2));

        AttendanceGenerationResponse response = generate(true);

        assertThat(response.daysGenerated()).isEqualTo(30);
        assertThat(response.unrosteredDaysGenerated()).isEqualTo(28);

        Map<LocalDate, AttendanceRecordResponse> byDate = recordsByDate();
        assertThat(byDate.get(PERIOD.atDay(1)).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(byDate.get(PERIOD.atDay(1)).shiftCode()).isEqualTo("GENERAL");
        assertThat(byDate.get(PERIOD.atDay(1)).workingHours()).isEqualByComparingTo("8.00");

        assertThat(byDate.get(PERIOD.atDay(3)).status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(byDate.get(PERIOD.atDay(3)).shiftCode()).isNull();

        assertThat(summary().getPresentDays()).isEqualByComparingTo("2.0");
        assertThat(summary().getLopDays()).isEqualByComparingTo("28.0");
    }

    // ---- the days that must not be swept up --------------------------------

    @Test
    @DisplayName("a mandatory holiday with no roster row is a holiday, never an absence")
    void anUnrosteredHolidayIsNotAnAbsence() {
        holidayRepository.save(Holiday.builder()
                .company(company).holidayName("Ganesh Chaturthi")
                .holidayDate(PERIOD.atDay(10)).optionalHoliday(false).build());

        generate(true);

        AttendanceRecordResponse day = recordsByDate().get(PERIOD.atDay(10));
        assertThat(day.status()).isEqualTo(AttendanceStatus.HOLIDAY);
        assertThat(day.holiday()).isTrue();

        // Invariant 4 holds through the new path too: a holiday is not a
        // working day, so it cannot become loss of pay.
        assertThat(summary().getWorkingDays()).isEqualTo(29);
        assertThat(summary().getLopDays()).isEqualByComparingTo("29.0");
    }

    @Test
    @DisplayName("approved leave on a day nobody rostered reads as leave, not absence")
    void unrosteredLeaveIsStillLeave() {
        leaveRequestRepository.save(LeaveRequest.builder()
                .userId(EMPLOYEE).leaveType(LeaveType.CASUAL_LEAVE)
                .fromDate(PERIOD.atDay(12)).toDate(PERIOD.atDay(13))
                .duration(LeaveDuration.FULL_DAY).totalDays(new BigDecimal("2.0"))
                .status(LeaveStatus.APPROVED).origin(LeaveOrigin.SELF_SERVICE)
                .appliedAt(Instant.now()).build());

        generate(true);

        Map<LocalDate, AttendanceRecordResponse> byDate = recordsByDate();
        assertThat(byDate.get(PERIOD.atDay(12)).status()).isEqualTo(AttendanceStatus.ON_LEAVE);
        assertThat(byDate.get(PERIOD.atDay(13)).status()).isEqualTo(AttendanceStatus.ON_LEAVE);

        // Paid leave, so the two days are absorbed rather than docked.
        assertThat(summary().getWorkingDays()).isEqualTo(30);
        assertThat(summary().getLopDays()).isEqualByComparingTo("28.0");
    }

    @Test
    @DisplayName("days before joining and after relieving are never filled")
    void theEmploymentWindowBoundsTheFill() {
        employeeRepository.deleteAll();
        saveEmployee(HR, Role.HR, LocalDate.of(2022, 1, 1), null);
        saveEmployee(EMPLOYEE, Role.EMPLOYEE, PERIOD.atDay(16), PERIOD.atDay(25));

        AttendanceGenerationResponse response = generate(true);

        assertThat(response.unrosteredDaysGenerated()).isEqualTo(10);
        assertThat(recordsByDate().keySet())
                .containsExactlyElementsOf(PERIOD.atDay(16).datesUntil(PERIOD.atDay(26)).toList());
    }

    // ---- evidence ----------------------------------------------------------

    @Test
    @DisplayName("punches on an unrostered day are kept, but the day has no shift to be measured against")
    void punchesOnAnUnrosteredDayAreKept() {
        punch(PERIOD.atDay(8).atTime(9, 0));
        punch(PERIOD.atDay(8).atTime(18, 0));

        generate(true);

        AttendanceRecordResponse day = recordsByDate().get(PERIOD.atDay(8));
        assertThat(day.firstIn()).isEqualTo(PERIOD.atDay(8).atTime(9, 0));
        assertThat(day.lastOut()).isEqualTo(PERIOD.atDay(8).atTime(18, 0));
        // No shift means no break, no grace and no expected length - there is
        // nothing to derive hours from, and guessing them would change pay.
        assertThat(day.workingHours()).isEqualByComparingTo("0.00");
        assertThat(day.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(day.invalidPunch()).isFalse();
    }

    // ---- opting out --------------------------------------------------------

    @Test
    @DisplayName("includeUnrostered=false keeps the roster-only behaviour exactly as it was")
    void optingOutGeneratesNothing() {
        AttendanceGenerationResponse response = generate(false);

        assertThat(response.daysGenerated()).isZero();
        assertThat(response.unrosteredDaysGenerated()).isZero();
        assertThat(response.employeesWithoutRoster()).containsExactly(EMPLOYEE);
        assertThat(attendanceService.getRecords(EMPLOYEE, PERIOD)).isEmpty();
    }

    // ---- correcting one ----------------------------------------------------

    @Test
    @DisplayName("an unrostered day can be corrected by forcing a status, and the correction survives a rerun")
    void anUnrosteredDayCanBeCorrected() {
        generate(true);

        AttendanceCorrectionRequest request = new AttendanceCorrectionRequest();
        request.setStatus(AttendanceStatus.PRESENT);
        request.setRemarks("Worked - roster was never assigned");
        request.setUpdatedBy(HR);

        AttendanceRecordResponse corrected = attendanceService.correctDay(EMPLOYEE, PERIOD.atDay(5), request);
        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(corrected.recordStatus()).isEqualTo(AttendanceRecordStatus.MANUAL);

        generate(true);
        assertThat(recordsByDate().get(PERIOD.atDay(5)).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(summary().getPresentDays()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("correcting an unrostered day with punches alone is refused - there is no shift to score them against")
    void correctingAnUnrosteredDayNeedsAStatus() {
        generate(true);

        AttendanceCorrectionRequest request = new AttendanceCorrectionRequest();
        request.setFirstIn(PERIOD.atDay(5).atTime(9, 0));
        request.setLastOut(PERIOD.atDay(5).atTime(18, 0));
        request.setRemarks("Punches from the register");
        request.setUpdatedBy(HR);

        assertThatThrownBy(() -> attendanceService.correctDay(EMPLOYEE, PERIOD.atDay(5), request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No shift is rostered");
    }

    // ---- fixtures ----------------------------------------------------------

    private AttendanceGenerationResponse generate(boolean includeUnrostered) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of(EMPLOYEE));
        request.setGeneratedBy(HR);
        request.setIncludeUnrostered(includeUnrostered);
        return attendanceService.generate(request);
    }

    private Map<LocalDate, AttendanceRecordResponse> recordsByDate() {
        return attendanceService.getRecords(EMPLOYEE, PERIOD).stream()
                .collect(Collectors.toMap(AttendanceRecordResponse::attendanceDate, r -> r,
                        (a, b) -> a, java.util.TreeMap::new));
    }

    private MonthlyAttendanceSummary summary() {
        return summaryRepository.findByUserIdAndMonth(EMPLOYEE, PERIOD.toString()).orElseThrow();
    }

    private void rosterAndPunch(LocalDate date) {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(date).shift(shift).weekOff(false).build());
        punch(date.atTime(9, 0));
        punch(date.atTime(18, 0));
    }

    private void punch(LocalDateTime at) {
        deviceLogRepository.save(DeviceLog.builder()
                .deviceLogId(punchId++).deviceId(9L).userId(EMPLOYEE).logDate(at).build());
    }

    private Employee saveEmployee(String userId, Role role, LocalDate joining, LocalDate relieving) {
        return employeeRepository.save(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(joining).relievingDate(relieving)
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
