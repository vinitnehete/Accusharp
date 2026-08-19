package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AttendanceService#statusesOn} is a batched (many employees, one day)
 * form of calling {@link AttendanceService#getDailyAttendance} once per
 * employee - built to remove the N+1 pattern {@code DashboardService} had
 * (one {@code getDailyAttendance} call per employee per day). It is meant to
 * reuse the exact same per-employee computation, just fetched with fewer
 * queries, so every scenario here is asserted against the single-employee
 * {@code getDailyAttendance} call as ground truth, not a hand-derived
 * expectation - the strongest available proof the batching didn't change
 * any employee's result.
 */
@SpringBootTest
class AttendanceStatusesOnBatchTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 15);
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final String HR = "HR900";
    private static final String STORED_PRESENT = "EMP900";
    private static final String PREVIEW_PRESENT = "EMP901";
    private static final String PREVIEW_ABSENT = "EMP902";
    private static final String NOT_SCHEDULED = "EMP903";
    private static final String ON_LEAVE = "EMP904";
    private static final String NIGHT_WITH_GAP = "EMP905";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;

    private Shift general;
    private Shift night;
    private long punchId = 90_000;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        punchId = 90_000;

        general = shiftRepository.save(Shift.builder()
                .shiftCode("GEN900").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        night = shiftRepository.save(Shift.builder()
                .shiftCode("NIGHT900").shiftName("Night")
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(8, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, Role.HR);
        saveEmployee(STORED_PRESENT, Role.EMPLOYEE);
        saveEmployee(PREVIEW_PRESENT, Role.EMPLOYEE);
        saveEmployee(PREVIEW_ABSENT, Role.EMPLOYEE);
        saveEmployee(NOT_SCHEDULED, Role.EMPLOYEE);
        saveEmployee(ON_LEAVE, Role.EMPLOYEE);
        saveEmployee(NIGHT_WITH_GAP, Role.EMPLOYEE);
    }

    @Test
    @DisplayName("statusesOn matches getDailyAttendance for every employee, whatever path each one takes")
    void batchedStatusesMatchIndividualLookups() {
        roster(STORED_PRESENT, DATE, general);
        punches(STORED_PRESENT, DATE.atTime(9, 5), DATE.atTime(18, 10));
        generate(STORED_PRESENT); // makes this one a STORED DailyAttendance row

        roster(PREVIEW_PRESENT, DATE, general);
        punches(PREVIEW_PRESENT, DATE.atTime(9, 0), DATE.atTime(18, 0)); // left as a live preview, never generated

        roster(PREVIEW_ABSENT, DATE, general); // scheduled, no punches at all -> absent, still a preview

        // NOT_SCHEDULED has no roster entry for DATE at all.

        roster(ON_LEAVE, DATE, general);
        leaveRequestRepository.save(LeaveRequest.builder()
                .userId(ON_LEAVE).leaveType(LeaveType.CASUAL_LEAVE)
                .fromDate(DATE).toDate(DATE).duration(LeaveDuration.FULL_DAY)
                .totalDays(new BigDecimal("1.0")).status(LeaveStatus.APPROVED)
                .origin(LeaveOrigin.HR_DIRECT).approverId(HR).appliedAt(Instant.now())
                .decidedAt(Instant.now()).build());

        // A night shift on DATE with nothing rostered the next day, so the
        // "next scheduled day" truncation window has to reach two days out to
        // find NIGHT_WITH_GAP's following shift - the edge case that would
        // diverge if the batched roster fetch used a wider date range per
        // employee instead of preserving each employee's own [date-1, date+1].
        roster(NIGHT_WITH_GAP, DATE, night);
        roster(NIGHT_WITH_GAP, DATE.plusDays(2), general);
        punches(NIGHT_WITH_GAP, DATE.atTime(18, 0), DATE.plusDays(1).atTime(7, 30));

        List<Employee> batch = employeeRepository.findAll().stream()
                .filter(e -> !e.getUserId().equals(HR))
                .toList();

        Map<String, AttendanceStatus> batched = attendanceService.statusesOn(batch, DATE);

        for (Employee employee : batch) {
            String userId = employee.getUserId();
            List<DailyAttendanceResponse> individual = attendanceService.getDailyAttendance(userId, DATE, DATE);

            if (individual.isEmpty()) {
                assertThat(batched).as(userId + " not scheduled - absent from the batch map")
                        .doesNotContainKey(userId);
            } else {
                assertThat(batched.get(userId)).as(userId + "'s batched status")
                        .isEqualTo(individual.get(0).status());
            }
        }

        // Concrete sanity checks on top of the equivalence proof above.
        assertThat(batched.get(STORED_PRESENT)).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(batched.get(PREVIEW_PRESENT)).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(batched.get(PREVIEW_ABSENT)).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(batched).doesNotContainKey(NOT_SCHEDULED);
        assertThat(batched.get(NIGHT_WITH_GAP)).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    @DisplayName("an empty employee list returns an empty map without querying anything")
    void emptyBatchIsEmptyMap() {
        assertThat(attendanceService.statusesOn(List.of(), DATE)).isEmpty();
    }

    // ---- fixtures ------------------------------------------------------

    private void roster(String userId, LocalDate date, Shift shift) {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(userId).shiftDate(date).shift(shift).weekOff(false).build());
    }

    private void punches(String userId, java.time.LocalDateTime... times) {
        List<DeviceLog> logs = new java.util.ArrayList<>();
        for (java.time.LocalDateTime at : times) {
            logs.add(DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(userId).logDate(at).build());
        }
        deviceLogRepository.saveAll(logs);
    }

    private void generate(String userId) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(MONTH);
        request.setUserIds(List.of(userId));
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    private void saveEmployee(String userId, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("CODE-" + userId).employeeName("Test " + userId)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("26000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false).build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}
