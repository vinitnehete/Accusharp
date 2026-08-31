package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceCorrectionRequest;
import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Generating attendance, correcting a day the device got wrong, and paying the
 * corrected figure.
 *
 * <p>The scenario is the one that motivated the feature: the biometric device
 * captured an entry punch but never the matching exit, so a day the employee
 * genuinely worked reads as an invalid punch and silently becomes loss of pay.
 */
@SpringBootTest
class AttendanceRegularisationTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final String EMPLOYEE = "EMP200";
    private static final String HR = "HR200";
    private static final LocalDate MISSED_OUT_PUNCH = PERIOD.atDay(23);
    private static final LocalDate NO_PUNCH_AT_ALL = PERIOD.atDay(24);

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private PayrollService payrollService;

    private long punchId = 10_000;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        punchId = 10_000;

        Shift morning = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-HR-200", "HR Head", Role.HR);
        saveEmployee(EMPLOYEE, "EMP-200", "Line Worker", Role.EMPLOYEE);

        List<ShiftSchedule> roster = new ArrayList<>();
        for (int day = 1; day <= 26; day++) {
            roster.add(ShiftSchedule.builder()
                    .userId(EMPLOYEE).shiftDate(PERIOD.atDay(day))
                    .shift(morning).weekOff(false).build());
        }
        shiftScheduleRepository.saveAll(roster);

        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= 22; day++) {
            punches.add(punch(PERIOD.atDay(day).atTime(6, 0)));
            punches.add(punch(PERIOD.atDay(day).atTime(15, 0)));
        }
        // The device recorded the entry and missed the exit.
        punches.add(punch(MISSED_OUT_PUNCH.atTime(6, 0)));
        deviceLogRepository.saveAll(punches);
    }

    @Test
    @DisplayName("generation stores one reviewable row per rostered day")
    void generationStoresOneRowPerRosteredDay() {
        AttendanceGenerationResponse response = generate(false);

        assertThat(response.daysGenerated()).isEqualTo(26);
        assertThat(response.manualPreserved()).isZero();

        List<AttendanceRecordResponse> records = attendanceService.getRecords(EMPLOYEE, PERIOD);
        assertThat(records).hasSize(26);
        assertThat(records).allMatch(r -> r.recordStatus() == AttendanceRecordStatus.GENERATED);
        assertThat(records).noneMatch(AttendanceRecordResponse::locked);

        AttendanceRecordResponse broken = recordOn(MISSED_OUT_PUNCH);
        assertThat(broken.status()).isEqualTo(AttendanceStatus.INVALID_PUNCH);
        assertThat(broken.invalidPunch()).isTrue();

        // The missed punch costs a day: 22 present, so 4 days of LOP.
        assertThat(monthly().presentDays()).isEqualByComparingTo("22");
        assertThat(monthly().lopDays()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("correcting the missed out-punch makes the day present and clears its LOP")
    void correctingAMissedOutPunchTurnsTheDayPresent() {
        generate(false);

        AttendanceRecordResponse corrected = correctPunches(MISSED_OUT_PUNCH,
                MISSED_OUT_PUNCH.atTime(6, 0), MISSED_OUT_PUNCH.atTime(15, 0));

        assertThat(corrected.recordStatus()).isEqualTo(AttendanceRecordStatus.MANUAL);
        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(corrected.invalidPunch()).isFalse();
        // 9 hours punch to punch, less the shift's 1 hour unpaid break.
        assertThat(corrected.workingHours()).isEqualByComparingTo("8.00");
        assertThat(corrected.remarks()).isEqualTo("Device missed the exit punch");
        assertThat(corrected.updatedBy()).isEqualTo(HR);

        assertThat(monthly().presentDays()).isEqualByComparingTo("23");
        assertThat(monthly().lopDays()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("a day with no punches at all can be declared outright")
    void forcedStatusDeclaresADayWithNoPunches() {
        generate(false);
        assertThat(recordOn(NO_PUNCH_AT_ALL).status()).isEqualTo(AttendanceStatus.ABSENT);

        AttendanceCorrectionRequest request = new AttendanceCorrectionRequest();
        request.setStatus(AttendanceStatus.PRESENT);
        request.setRemarks("Worked off-site, device unreachable");
        request.setUpdatedBy(HR);

        AttendanceRecordResponse declared = attendanceService.correctDay(EMPLOYEE, NO_PUNCH_AT_ALL, request);

        assertThat(declared.recordStatus()).isEqualTo(AttendanceRecordStatus.MANUAL);
        assertThat(declared.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(declared.workingHours()).isEqualByComparingTo("8.00");
        assertThat(declared.firstIn()).isNull();
    }

    @Test
    @DisplayName("regenerating preserves manual corrections instead of recomputing them away")
    void regenerationPreservesManualCorrections() {
        generate(false);
        correctPunches(MISSED_OUT_PUNCH, MISSED_OUT_PUNCH.atTime(6, 0), MISSED_OUT_PUNCH.atTime(15, 0));

        AttendanceGenerationResponse rerun = generate(false);

        assertThat(rerun.manualPreserved()).isEqualTo(1);
        assertThat(rerun.daysGenerated()).isEqualTo(25);

        AttendanceRecordResponse stillCorrected = recordOn(MISSED_OUT_PUNCH);
        assertThat(stillCorrected.recordStatus()).isEqualTo(AttendanceRecordStatus.MANUAL);
        assertThat(stillCorrected.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(monthly().lopDays()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("a correction can be discarded deliberately with overwriteManual")
    void regenerationOverwritesManualWhenAsked() {
        generate(false);
        correctPunches(MISSED_OUT_PUNCH, MISSED_OUT_PUNCH.atTime(6, 0), MISSED_OUT_PUNCH.atTime(15, 0));

        AttendanceGenerationResponse rerun = generate(true);

        assertThat(rerun.manualPreserved()).isZero();
        assertThat(rerun.daysGenerated()).isEqualTo(26);
        assertThat(recordOn(MISSED_OUT_PUNCH).recordStatus()).isEqualTo(AttendanceRecordStatus.GENERATED);
        assertThat(recordOn(MISSED_OUT_PUNCH).status()).isEqualTo(AttendanceStatus.INVALID_PUNCH);
    }

    @Test
    @DisplayName("reading the month never overwrites a correction")
    void aMonthlyReadNeverOverwritesACorrection() {
        generate(false);
        correctPunches(MISSED_OUT_PUNCH, MISSED_OUT_PUNCH.atTime(6, 0), MISSED_OUT_PUNCH.atTime(15, 0));

        // The regression this whole design exists to prevent: before stored
        // attendance, each of these recomputed from punches and wiped the fix.
        attendanceService.getMonthlyAttendance(EMPLOYEE, PERIOD);
        attendanceService.getDailyAttendance(EMPLOYEE, PERIOD.atDay(1), PERIOD.atEndOfMonth());
        attendanceService.syncSummaries(PERIOD);

        assertThat(recordOn(MISSED_OUT_PUNCH).recordStatus()).isEqualTo(AttendanceRecordStatus.MANUAL);
        assertThat(recordOn(MISSED_OUT_PUNCH).status()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    @DisplayName("payroll pays the corrected attendance, not the raw punches")
    void payrollPaysTheCorrectedAttendance() {
        generate(false);
        correctPunches(MISSED_OUT_PUNCH, MISSED_OUT_PUNCH.atTime(6, 0), MISSED_OUT_PUNCH.atTime(15, 0));

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getPresentDays()).isEqualByComparingTo("23");
        assertThat(payroll.getLopDays()).isEqualByComparingTo("3");
        // Salaried against the full 30-day September calendar (week-offs
        // included), reduced only by the 3 LOP days: 30 - 3 = 27, not 23.
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("27");
    }

    @Test
    @DisplayName("payroll locks the month, and unlocking reopens it for correction")
    void payrollLocksAttendanceUntilExplicitlyUnlocked() {
        generate(false);
        payrollService.generate(payrollRequest());

        assertThat(attendanceService.getRecords(EMPLOYEE, PERIOD)).allMatch(AttendanceRecordResponse::locked);

        assertThatThrownBy(() -> correctPunches(MISSED_OUT_PUNCH,
                MISSED_OUT_PUNCH.atTime(6, 0), MISSED_OUT_PUNCH.atTime(15, 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("locked");

        // A locked month is also immune to a regeneration run.
        assertThat(generate(true).lockedSkipped()).isEqualTo(26);

        assertThat(attendanceService.unlockMonth(EMPLOYEE, PERIOD, HR)).isEqualTo(26);

        AttendanceRecordResponse corrected = correctPunches(MISSED_OUT_PUNCH,
                MISSED_OUT_PUNCH.atTime(6, 0), MISSED_OUT_PUNCH.atTime(15, 0));
        assertThat(corrected.status()).isEqualTo(AttendanceStatus.PRESENT);

        // The paid revision is untouched until payroll is regenerated.
        Payroll regenerated = payrollService.regenerate(payrollRequest());
        assertThat(regenerated.getRevision()).isEqualTo(2);
        assertThat(regenerated.getPayableDays()).isEqualByComparingTo("27");
    }

    @Test
    @DisplayName("only HR or admin may correct a day")
    void correctionRequiresHrOrAdmin() {
        generate(false);

        AttendanceCorrectionRequest request = new AttendanceCorrectionRequest();
        request.setFirstIn(MISSED_OUT_PUNCH.atTime(6, 0));
        request.setLastOut(MISSED_OUT_PUNCH.atTime(15, 0));
        request.setRemarks("Fixing my own attendance");
        request.setUpdatedBy(EMPLOYEE);

        assertThatThrownBy(() -> attendanceService.correctDay(EMPLOYEE, MISSED_OUT_PUNCH, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("HR or ADMIN");
    }

    @Test
    @DisplayName("a correction must carry either punch times or a status")
    void correctionNeedsSomethingToApply() {
        generate(false);

        AttendanceCorrectionRequest empty = new AttendanceCorrectionRequest();
        empty.setRemarks("No idea");
        empty.setUpdatedBy(HR);

        assertThatThrownBy(() -> attendanceService.correctDay(EMPLOYEE, MISSED_OUT_PUNCH, empty))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---- fixtures ----------------------------------------------------------

    private AttendanceGenerationResponse generate(boolean overwriteManual) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of(EMPLOYEE));
        request.setGeneratedBy(HR);
        request.setOverwriteManual(overwriteManual);
        return attendanceService.generate(request);
    }

    private AttendanceRecordResponse correctPunches(LocalDate date, LocalDateTime in, LocalDateTime out) {
        AttendanceCorrectionRequest request = new AttendanceCorrectionRequest();
        request.setFirstIn(in);
        request.setLastOut(out);
        request.setRemarks("Device missed the exit punch");
        request.setUpdatedBy(HR);
        return attendanceService.correctDay(EMPLOYEE, date, request);
    }

    private AttendanceRecordResponse recordOn(LocalDate date) {
        return attendanceService.getRecords(EMPLOYEE, PERIOD).stream()
                .filter(record -> record.attendanceDate().equals(date))
                .findFirst().orElseThrow();
    }

    private MonthlyAttendanceResponse monthly() {
        return attendanceService.getMonthlyAttendance(EMPLOYEE, PERIOD);
    }

    private PayrollRequest payrollRequest() {
        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(EMPLOYEE);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        return request;
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

    private DeviceLog punch(LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(EMPLOYEE).logDate(at).build();
    }
}
