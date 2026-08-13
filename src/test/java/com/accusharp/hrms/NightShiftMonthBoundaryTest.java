package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceStatus;
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
 * Night shifts that straddle a month boundary.
 *
 * <p>A night shift belongs to the date it <em>starts</em> on, so a shift begun
 * on 30 June is a June day even though the employee punches out on 1 July. The
 * awkward cases are the two ends of the month: June's last night shift reaches
 * forward into July, and May's reaches forward into June.
 */
@SpringBootTest
class NightShiftMonthBoundaryTest {

    private static final YearMonth JUNE = YearMonth.of(2026, 6);
    private static final LocalDate LAST_DAY = LocalDate.of(2026, 6, 30);
    private static final String EMPLOYEE = "EMP400";
    private static final String HR = "HR400";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;

    private Shift night;
    private Shift morning;
    private Shift general;
    private long punchId = 40_000;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        punchId = 40_000;

        night = shiftRepository.save(Shift.builder()
                .shiftCode("NIGHT").shiftName("Night")
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(8, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        morning = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        general = shiftRepository.save(Shift.builder()
                .shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-HR-400", "HR Head", Role.HR);
        saveEmployee(EMPLOYEE, "EMP-400", "Night Worker", Role.EMPLOYEE);
    }

    @Test
    @DisplayName("a night shift started on the last day of the month is a June day")
    void nightShiftOnTheLastDayOfTheMonth() {
        rosterNight(JUNE.atDay(29));
        rosterNight(LAST_DAY);

        punches(JUNE.atDay(29).atTime(18, 0), LAST_DAY.atTime(7, 0));
        // Clocked in on 30 June, out on 1 July - the next month.
        punches(LAST_DAY.atTime(18, 5), LocalDate.of(2026, 7, 1).atTime(7, 50));

        generateJune();

        List<DailyAttendanceResponse> days = attendanceService
                .getDailyAttendance(EMPLOYEE, JUNE.atDay(1), JUNE.atEndOfMonth());
        DailyAttendanceResponse lastDay = days.stream()
                .filter(d -> d.attendanceDate().equals(LAST_DAY)).findFirst().orElseThrow();

        assertThat(lastDay.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(lastDay.firstIn()).isEqualTo(LAST_DAY.atTime(18, 5));
        assertThat(lastDay.lastOut()).isEqualTo(LocalDate.of(2026, 7, 1).atTime(7, 50));
        // 13h45m punch to punch, less the 1h unpaid break.
        assertThat(lastDay.workingHours()).isEqualByComparingTo("12.75");

        MonthlyAttendanceResponse june = attendanceService.getMonthlyAttendance(EMPLOYEE, JUNE);
        assertThat(june.workingDays()).isEqualTo(2);
        assertThat(june.presentDays()).isEqualByComparingTo("2");
        assertThat(june.lopDays()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("July does not steal the out-punch belonging to June's last night shift")
    void julyDoesNotDoubleCountTheHandoverPunch() {
        rosterNight(LAST_DAY);
        // 1 July: a general shift, worked normally.
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(LocalDate.of(2026, 7, 1))
                .shift(general).weekOff(false).build());

        punches(LAST_DAY.atTime(18, 5), LocalDate.of(2026, 7, 1).atTime(7, 50));
        punches(LocalDate.of(2026, 7, 1).atTime(9, 0), LocalDate.of(2026, 7, 1).atTime(18, 0));

        generateJune();
        generate(YearMonth.of(2026, 7));

        DailyAttendanceResponse june30 = dayOf(JUNE, LAST_DAY);
        DailyAttendanceResponse july1 = dayOf(YearMonth.of(2026, 7), LocalDate.of(2026, 7, 1));

        assertThat(june30.firstIn()).isEqualTo(LAST_DAY.atTime(18, 5));
        assertThat(june30.lastOut()).isEqualTo(LocalDate.of(2026, 7, 1).atTime(7, 50));

        // Without an exclusive window the night shift's 12:00 cut-off would have
        // swallowed 1 July's 09:00 entry and reported it as June's exit.
        assertThat(july1.firstIn()).isEqualTo(LocalDate.of(2026, 7, 1).atTime(9, 0));
        assertThat(july1.lastOut()).isEqualTo(LocalDate.of(2026, 7, 1).atTime(18, 0));
    }

    @Test
    @DisplayName("June's first day does not absorb May's last night shift")
    void juneDoesNotAbsorbMaysHandoverPunch() {
        // 31 May night shift, out on 1 June. 1 June is itself a morning shift.
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(LocalDate.of(2026, 5, 31))
                .shift(night).weekOff(false).build());
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(JUNE.atDay(1))
                .shift(general).weekOff(false).build());

        punches(LocalDate.of(2026, 5, 31).atTime(18, 0), JUNE.atDay(1).atTime(7, 45));
        punches(JUNE.atDay(1).atTime(9, 0), JUNE.atDay(1).atTime(18, 0));

        generateJune();

        DailyAttendanceResponse june1 = dayOf(JUNE, JUNE.atDay(1));
        assertThat(june1.firstIn()).isEqualTo(JUNE.atDay(1).atTime(9, 0));
        assertThat(june1.lastOut()).isEqualTo(JUNE.atDay(1).atTime(18, 0));
    }

    @Test
    @DisplayName("a correction can span midnight into the next month")
    void aCorrectionCanSpanTheMonthBoundary() {
        rosterNight(LAST_DAY);
        // Entry captured on 30 June, exit on 1 July missed by the device.
        punches(LAST_DAY.atTime(18, 0));

        generateJune();
        assertThat(dayOf(JUNE, LAST_DAY).status()).isEqualTo(AttendanceStatus.INVALID_PUNCH);

        var request = new com.accusharp.hrms.dto.AttendanceCorrectionRequest();
        request.setFirstIn(LAST_DAY.atTime(18, 0));
        request.setLastOut(LocalDate.of(2026, 7, 1).atTime(3, 0));
        request.setRemarks("Night shift exit missed at the month boundary");
        request.setUpdatedBy(HR);

        var corrected = attendanceService.correctDay(EMPLOYEE, LAST_DAY, request);

        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(corrected.lastOut()).isEqualTo(LocalDate.of(2026, 7, 1).atTime(3, 0));
        // 9 hours punch to punch, less the 1h break.
        assertThat(corrected.workingHours()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("a night shift on 31 December belongs to December, not the new year")
    void nightShiftAcrossTheYearBoundary() {
        LocalDate newYearsEve = LocalDate.of(2026, 12, 31);
        YearMonth december = YearMonth.of(2026, 12);

        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(newYearsEve).shift(night).weekOff(false).build());

        punches(newYearsEve.atTime(18, 0), LocalDate.of(2027, 1, 1).atTime(6, 30));

        generate(december);

        DailyAttendanceResponse day = dayOf(december, newYearsEve);
        assertThat(day.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(day.lastOut()).isEqualTo(LocalDate.of(2027, 1, 1).atTime(6, 30));
        // 12h30m punch to punch, less the 1h break.
        assertThat(day.workingHours()).isEqualByComparingTo("11.50");

        assertThat(attendanceService.getMonthlyAttendance(EMPLOYEE, december).presentDays())
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a punch is never counted by two days, even on an impossible roster")
    void aPunchBelongsToExactlyOneDay() {
        // 18:00-08:00 followed by 06:00-15:00 cannot actually be worked, but the
        // roster allows it - and the handover punch must still land on one day only.
        rosterNight(LAST_DAY);
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(LocalDate.of(2026, 7, 1))
                .shift(morning).weekOff(false).build());

        punches(LAST_DAY.atTime(18, 0), LocalDate.of(2026, 7, 1).atTime(7, 45));

        generateJune();
        generate(YearMonth.of(2026, 7));

        DailyAttendanceResponse june30 = dayOf(JUNE, LAST_DAY);
        DailyAttendanceResponse july1 = dayOf(YearMonth.of(2026, 7), LocalDate.of(2026, 7, 1));

        long claims = 0;
        if (LocalDate.of(2026, 7, 1).atTime(7, 45).equals(june30.lastOut())) {
            claims++;
        }
        if (LocalDate.of(2026, 7, 1).atTime(7, 45).equals(july1.firstIn())) {
            claims++;
        }
        assertThat(claims)
                .as("the 07:45 handover punch must belong to exactly one day")
                .isEqualTo(1);
    }

    // ---- fixtures ----------------------------------------------------------

    private DailyAttendanceResponse dayOf(YearMonth month, LocalDate date) {
        return attendanceService.getMonthlyAttendance(EMPLOYEE, month).days().stream()
                .filter(d -> d.attendanceDate().equals(date)).findFirst().orElseThrow();
    }

    private void rosterNight(LocalDate date) {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(date).shift(night).weekOff(false).build());
    }

    private void punches(LocalDateTime... times) {
        List<DeviceLog> logs = new ArrayList<>();
        for (LocalDateTime at : times) {
            logs.add(DeviceLog.builder().deviceLogId(punchId++).deviceId(1L)
                    .userId(EMPLOYEE).logDate(at).build());
        }
        deviceLogRepository.saveAll(logs);
    }

    private void generateJune() {
        generate(JUNE);
    }

    private void generate(YearMonth month) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(month);
        request.setUserIds(List.of(EMPLOYEE));
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    private void saveEmployee(String userId, String code, String name, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode(code).employeeName(name)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("26000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false).build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}
