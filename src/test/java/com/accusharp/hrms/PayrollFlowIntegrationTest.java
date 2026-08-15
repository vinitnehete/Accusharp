package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.dto.SalarySlipResponse;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.PayrollStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.ConflictException;
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
import com.accusharp.hrms.service.leave.LeaveService;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.service.payroll.SalarySlipService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end payroll: roster, punches, approved leave, LOP, salary and slip.
 * Uses the specification's worked example - 26 working days, 23 attended,
 * 2 days approved leave, so exactly 1 day of loss of pay.
 */
@SpringBootTest
class PayrollFlowIntegrationTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 8);
    private static final String EMPLOYEE = "EMP100";
    private static final String HR = "HR100";

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
    @Autowired private LeaveService leaveService;
    @Autowired private PayrollService payrollService;
    @Autowired private SalarySlipService salarySlipService;

    private long punchId = 1;

    @BeforeEach
    void setUp() {
        // The context is shared across test methods, so every table this flow
        // touches is reset - otherwise leave from one test leaks into the next.
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        punchId = 1;

        Shift morning = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, "EMP-HR-100", "HR Head", Role.HR, new BigDecimal("50000"));
        saveEmployee(EMPLOYEE, "EMP-100", "Test Worker", Role.EMPLOYEE, new BigDecimal("26000"));

        // 26 scheduled working days: the 1st to the 26th of the month.
        List<ShiftSchedule> roster = new ArrayList<>();
        for (int day = 1; day <= 26; day++) {
            roster.add(ShiftSchedule.builder()
                    .userId(EMPLOYEE)
                    .shiftDate(PERIOD.atDay(day))
                    .shift(morning)
                    .weekOff(false)
                    .build());
        }
        shiftScheduleRepository.saveAll(roster);

        // Attended on the 1st to the 23rd - a full 8 hours each day.
        List<DeviceLog> punches = new ArrayList<>();
        for (int day = 1; day <= 23; day++) {
            punches.add(punch(PERIOD.atDay(day).atTime(6, 0)));
            punches.add(punch(PERIOD.atDay(day).atTime(15, 0)));
        }
        deviceLogRepository.saveAll(punches);
    }

    @Test
    @DisplayName("attendance derives 23 present days and 1 LOP day after approved leave")
    void attendanceProducesTheExpectedLop() {
        approveTwoDaysLeave();
        generateAttendance();

        MonthlyAttendanceResponse attendance = attendanceService.getMonthlyAttendance(EMPLOYEE, PERIOD);

        assertThat(attendance.workingDays()).isEqualTo(26);
        assertThat(attendance.presentDays()).isEqualByComparingTo("23");
        assertThat(attendance.leaveDays()).isEqualByComparingTo("2");
        assertThat(attendance.lopDays()).isEqualByComparingTo("1");
        assertThat(attendance.totalHours()).isEqualByComparingTo("184.00");
    }

    @Test
    @DisplayName("payroll prorates earnings to the 30 payable days of the calendar month")
    void payrollProratesByPayableDays() {
        approveTwoDaysLeave();
        generateAttendance();

        Payroll payroll = payrollService.generate(payrollRequest());

        assertThat(payroll.getWorkingDays()).isEqualTo(26);
        assertThat(payroll.getPresentDays()).isEqualByComparingTo("23");
        assertThat(payroll.getPaidLeaveDays()).isEqualByComparingTo("2");
        assertThat(payroll.getLopDays()).isEqualByComparingTo("1");
        // Salaried, not day-wise: paid the full 31-day calendar month (week-offs
        // included), reduced only by the 1 LOP day attendance found - 30, not
        // the 25 you'd get by prorating against working days instead.
        assertThat(payroll.getPayableDays()).isEqualByComparingTo("30");

        // Basic is 50% of a 26000 gross, prorated 30/31.
        assertThat(payroll.getEarnBasicDA()).isEqualByComparingTo("12580.65");
        assertThat(payroll.getNetSalary())
                .isEqualByComparingTo(payroll.getTotalEarnings().subtract(payroll.getTotalDeduction()));
        assertThat(payroll.getStatus()).isEqualTo(PayrollStatus.GENERATED);
        assertThat(payroll.getRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("a period can only be generated once")
    void generatingTwiceIsAConflict() {
        generateAttendance();
        payrollService.generate(payrollRequest());

        assertThatThrownBy(() -> payrollService.generate(payrollRequest()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("regenerating supersedes the old revision instead of overwriting it")
    void regenerationKeepsHistoryImmutable() {
        generateAttendance();
        Payroll first = payrollService.generate(payrollRequest());
        BigDecimal originalNet = first.getNetSalary();

        // A later salary revision must not change what was already paid.
        Employee employee = employeeRepository.findByUserId(EMPLOYEE).orElseThrow();
        employee.setGrossSalary(new BigDecimal("40000"));
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);

        Payroll second = payrollService.regenerate(payrollRequest());

        assertThat(second.getRevision()).isEqualTo(2);
        assertThat(second.getNetSalary()).isNotEqualByComparingTo(originalNet);

        List<Payroll> revisions = payrollService.getRevisions(EMPLOYEE, PERIOD.getMonthValue(),
                PERIOD.getYear());
        assertThat(revisions).hasSize(2);

        Payroll superseded = revisions.stream()
                .filter(p -> p.getRevision() == 1).findFirst().orElseThrow();
        assertThat(superseded.getStatus()).isEqualTo(PayrollStatus.SUPERSEDED);
        assertThat(superseded.getNetSalary()).isEqualByComparingTo(originalNet);
    }

    @Test
    @DisplayName("the salary slip reports exactly what payroll recorded")
    void salarySlipMirrorsPayroll() {
        generateAttendance();
        Payroll payroll = payrollService.generate(payrollRequest());

        SalarySlipResponse slip = salarySlipService.getSlip(EMPLOYEE, PERIOD.getMonthValue(),
                PERIOD.getYear());

        assertThat(slip.employeeName()).isEqualTo("Test Worker");
        assertThat(slip.period()).isEqualTo("August 2026");
        assertThat(slip.netSalary()).isEqualByComparingTo(payroll.getNetSalary());
        assertThat(slip.totalEarnings()).isEqualByComparingTo(payroll.getTotalEarnings());
        assertThat(slip.earnings()).extracting(SalarySlipResponse.Line::label).contains("Basic + DA");
        assertThat(slip.netSalaryInWords()).endsWith("Only");

        String html = salarySlipService.renderHtml(EMPLOYEE, PERIOD.getMonthValue(), PERIOD.getYear());
        assertThat(html).contains("Test Worker").contains("Net Salary");
    }

    @Test
    @DisplayName("cancelling an approved leave returns the days to the balance")
    void cancellingLeaveRestoresBalance() {
        var leave = approveTwoDaysLeave();

        LeaveDecisionRequest decision = new LeaveDecisionRequest();
        decision.setApproverId(HR);
        leaveService.cancel(leave.id(), decision);

        MonthlyAttendanceResponse attendance = attendanceService.getMonthlyAttendance(EMPLOYEE, PERIOD);

        // Without the leave, those two days become loss of pay as well.
        assertThat(attendance.leaveDays()).isEqualByComparingTo("0");
        assertThat(attendance.lopDays()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("payroll refuses to run against attendance nobody generated")
    void payrollRequiresGeneratedAttendance() {
        assertThatThrownBy(() -> payrollService.generate(payrollRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Attendance has not been generated");
    }

    // ---- fixtures ----------------------------------------------------------

    private void generateAttendance() {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of(EMPLOYEE));
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    private com.accusharp.hrms.dto.LeaveResponse approveTwoDaysLeave() {
        LeaveRequestPayload payload = new LeaveRequestPayload();
        payload.setUserId(EMPLOYEE);
        payload.setLeaveType(LeaveType.CASUAL_LEAVE);
        payload.setFromDate(PERIOD.atDay(24));
        payload.setToDate(PERIOD.atDay(25));
        payload.setDuration(LeaveDuration.FULL_DAY);
        payload.setReason("Family function");

        var applied = leaveService.apply(payload);

        LeaveDecisionRequest decision = new LeaveDecisionRequest();
        decision.setApproverId(HR);
        decision.setComments("Approved");
        return leaveService.approve(applied.id(), decision);
    }

    private PayrollRequest payrollRequest() {
        PayrollRequest request = new PayrollRequest();
        request.setEmployeeId(EMPLOYEE);
        request.setMonth(PERIOD.getMonthValue());
        request.setYear(PERIOD.getYear());
        request.setGeneratedBy(HR);
        return request;
    }

    private void saveEmployee(String userId, String code, String name, Role role, BigDecimal gross) {
        Employee employee = Employee.builder()
                .userId(userId)
                .employeeCode(code)
                .employeeName(name)
                .status(EmployeeStatus.PERMANENT)
                .recordStatus(RecordStatus.ACTIVE)
                .role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(gross)
                .pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250"))
                .otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }

    private DeviceLog punch(java.time.LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(punchId++).deviceId(1L).userId(EMPLOYEE).logDate(at).build();
    }
}
