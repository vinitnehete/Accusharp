package com.accusharp.hrms;

import com.accusharp.hrms.dto.ShiftRequest;
import com.accusharp.hrms.entity.AuditLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.AuditLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.service.shift.ShiftSchedulingService;
import com.accusharp.hrms.service.shift.ShiftService;
import com.accusharp.hrms.dto.ShiftAssignmentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two guardrails around bad roster data.
 *
 * <p>Neither of these stops the attendance engine working - a shift owns its
 * own span whatever is assigned after it, so the engine no longer needs
 * protecting from the roster. They exist because an impossible roster is
 * almost always a mistake, and it used to stay invisible until somebody read a
 * month's loss of pay and worked backwards.
 */
@SpringBootTest
class RosterGuardrailsTest {

    private static final String EMPLOYEE = "EMP700";
    private static final String HR = "HR700";
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 15);

    @Autowired private ShiftService shiftService;
    @Autowired private ShiftSchedulingService shiftSchedulingService;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    private Shift night;
    private Shift morning;

    @BeforeEach
    void setUp() {
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        auditLogRepository.deleteAll();

        night = shiftRepository.save(Shift.builder()
                .shiftCode("NIGHT").shiftName("Night")
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(8, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(120)
                .overtimeWindowMinutes(240).build());
        morning = shiftRepository.save(Shift.builder()
                .shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(19, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(120)
                .overtimeWindowMinutes(240).build());

        saveEmployee(HR, Role.HR);
        saveEmployee(EMPLOYEE, Role.EMPLOYEE);
    }

    // ---- shift definition --------------------------------------------------

    @Test
    @DisplayName("a shift that starts and ends at the same time is rejected")
    void equalStartAndEndTimesAreRejected() {
        // Left alone this becomes a silent 24-hour shift: crossesMidnight reads
        // true, the span is PT24H, and every day books sixteen hours of overtime.
        assertThatThrownBy(() -> shiftService.create(shiftRequest(LocalTime.of(8, 0), LocalTime.of(8, 0))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("an end time before the start time is still a night shift, not an error")
    void anEndBeforeTheStartIsStillHowANightShiftIsDeclared() {
        Shift created = shiftService.create(shiftRequest(LocalTime.of(18, 0), LocalTime.of(8, 0)));

        assertThat(created.crossesMidnight()).isTrue();
        assertThat(created.span()).hasHours(14);
    }

    // ---- rest gap ----------------------------------------------------------

    @Test
    @DisplayName("assigning a morning shift the day after a night shift is flagged, not blocked")
    void anUnworkableConsecutivePairIsAudited() {
        assign(MONDAY, "NIGHT");
        // NIGHT runs to 08:00 on the 16th; MORNING starts at 06:00 that same day.
        assign(MONDAY.plusDays(1), "MORNING");

        assertThat(shiftScheduleRepository.findByUserIdAndShiftDate(EMPLOYEE, MONDAY.plusDays(1)))
                .as("the assignment must still succeed - HR overrides rosters for real reasons")
                .isPresent();
        assertThat(restGapAudits())
                .as("but the impossible pair is recorded")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a workable pair raises nothing")
    void aWorkablePairIsSilent() {
        assign(MONDAY, "MORNING");
        assign(MONDAY.plusDays(1), "MORNING");

        assertThat(restGapAudits()).isEmpty();
    }

    @Test
    @DisplayName("a night shift followed by a day off raises nothing")
    void aDayOffAfterANightShiftIsSilent() {
        assign(MONDAY, "NIGHT");
        ShiftAssignmentRequest request = new ShiftAssignmentRequest();
        request.setUserId(EMPLOYEE);
        request.setShiftDate(MONDAY.plusDays(1));
        request.setShiftCode("MORNING");
        request.setWeekOff(true);
        request.setAssignedBy(HR);
        shiftSchedulingService.assign(request);

        assertThat(restGapAudits())
                .as("nobody is expected to work a day off, so there is no conflict to report")
                .isEmpty();
    }

    // ---- fixtures ----------------------------------------------------------

    private List<AuditLog> restGapAudits() {
        return auditLogRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, 100)).stream()
                .filter(entry -> "SHIFT_SCHEDULE_REST_GAP".equals(entry.getAction()))
                .toList();
    }

    private void assign(LocalDate date, String shiftCode) {
        ShiftAssignmentRequest request = new ShiftAssignmentRequest();
        request.setUserId(EMPLOYEE);
        request.setShiftDate(date);
        request.setShiftCode(shiftCode);
        request.setWeekOff(false);
        request.setAssignedBy(HR);
        shiftSchedulingService.assign(request);
    }

    private ShiftRequest shiftRequest(LocalTime start, LocalTime end) {
        ShiftRequest request = new ShiftRequest();
        request.setShiftCode("CUSTOM_" + start.getHour() + "_" + end.getHour());
        request.setShiftName("Custom");
        request.setStartTime(start);
        request.setEndTime(end);
        request.setWorkingHours(8);
        request.setBreakMinutes(0);
        request.setGraceMinutes(15);
        request.setOvertimeWindowMinutes(240);
        return request;
    }

    private void saveEmployee(String userId, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("C-" + userId).employeeName(userId)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("26000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false).build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}
