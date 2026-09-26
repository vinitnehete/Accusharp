package com.accusharp.hrms.leave;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveCredit;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.LeaveRuleRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.SalaryRevisionRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.leave.LeaveBalanceService;
import com.accusharp.hrms.service.payroll.PayrollService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Earned leave is credited when the month's payroll is generated.
 *
 * <p>That is the one moment attendance is guaranteed not to move again - payroll
 * locks the month as it reads it - so it is the only moment a credit computed
 * from that attendance is safe to post. Crediting earlier would credit from
 * figures HR may still correct.
 *
 * <p>Same fixture as {@code PayrollFlowIntegrationTest}: August 2026, the 1st to
 * the 26th rostered, 8 paid hours a day. There is no {@code GENERAL} shift, so
 * the rest of the month is not derived into working days.
 */
@SpringBootTest
class EarnedLeavePayrollHookTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 8);
    private static final String HR = "HR990";
    private static final String EMPLOYEE = "PH990";

    @Autowired private PayrollService payrollService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private LeaveBalanceService leaveBalanceService;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private LeaveRuleRepository leaveRuleRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private SalaryRevisionRepository salaryRevisionRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;

    private long punchId = 99_000;

    @BeforeEach
    void setUp() {
        clean();
        punchId = 99_000;

        Shift morning = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, Role.HR);
        saveEmployee(EMPLOYEE, Role.EMPLOYEE);

        List<ShiftSchedule> roster = new ArrayList<>();
        for (int day = 1; day <= 26; day++) {
            roster.add(ShiftSchedule.builder()
                    .userId(EMPLOYEE).shiftDate(PERIOD.atDay(day)).shift(morning).weekOff(false).build());
        }
        shiftScheduleRepository.saveAll(roster);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("generating payroll for a full month credits 1.5 earned leave")
    void payrollGenerationCreditsTheMonth() {
        elRule();
        attended(26);
        generateAttendance();

        payrollService.generate(payrollRequest());

        assertThat(elQuota()).isEqualByComparingTo("1.5");
        assertThat(leaveCreditRepository.findAllByUserIdAndLeaveYearOrderByPeriodAsc(EMPLOYEE, 2026))
                .extracting(LeaveCredit::getPeriod).containsExactly("2026-08");
    }

    @Test
    @DisplayName("three days of LOP: 23 counted earns the legal 1.2, not the 1.0 step")
    void lopReducesTheCredit() {
        elRule();
        attended(23);
        generateAttendance();

        payrollService.generate(payrollRequest());

        assertThat(elQuota()).isEqualByComparingTo("1.2");
    }

    @Test
    @DisplayName("regenerating payroll does not credit the month a second time")
    void regeneratingCreditsOnce() {
        elRule();
        attended(26);
        generateAttendance();

        payrollService.generate(payrollRequest());
        payrollService.regenerate(payrollRequest());

        assertThat(elQuota()).isEqualByComparingTo("1.5");
        assertThat(leaveCreditRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("with no EL rule, payroll is exactly as before and nothing is credited")
    void noRulePayrollIsUnchanged() {
        attended(26);
        generateAttendance();

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getStatus()).isEqualTo(PayrollStatus.GENERATED);
        assertThat(elQuota()).isEqualByComparingTo("0");
        assertThat(leaveCreditRepository.findAll()).isEmpty();
    }

    // ---- fixtures ----------------------------------------------------------

    private BigDecimal elQuota() {
        return leaveBalanceService.getOrCreate(EMPLOYEE, 2026, LeaveType.EARNED_LEAVE).getQuota();
    }

    private void elRule() {
        // Shared rule (no company), matching the fixture's company-less employees.
        leaveRuleRepository.save(LeaveRule.builder()
                .scope(RuleScope.COMPANY).scopeRef(LeaveRule.ANY)
                .leaveType(LeaveType.EARNED_LEAVE).grantMethod(LeaveGrant.EARNED_BY_ATTENDANCE)
                .carryForwardCap(new BigDecimal("30"))
                .effectiveFrom(LocalDate.of(2026, 1, 1)).enabled(true)
                .build());
    }

    private void attended(int days) {
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            punches.add(punch(PERIOD.atDay(day).atTime(6, 0)));
            punches.add(punch(PERIOD.atDay(day).atTime(15, 0)));
        }
        deviceLogRepository.saveAll(punches);
    }

    private void generateAttendance() {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of(EMPLOYEE));
        request.setGeneratedBy(HR);
        request.setIncludeUnrostered(false);
        attendanceService.generate(request);
    }

    private PayrollRequest payrollRequest() {
        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(EMPLOYEE);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        return request;
    }

    private void saveEmployee(String userId, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("26000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }

    private DeviceLog punch(java.time.LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(EMPLOYEE).logDate(at).build();
    }

    private void clean() {
        leaveCreditRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        salaryRevisionRepository.deleteAll();
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
    }
}
