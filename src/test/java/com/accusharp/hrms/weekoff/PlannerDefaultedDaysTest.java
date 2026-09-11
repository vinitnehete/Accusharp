package com.accusharp.hrms.weekoff;

import com.accusharp.hrms.dto.MonthlyPlannerResponse;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.shift.ShiftSchedulingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner grid, once permanent employees stopped having stored roster rows.
 *
 * <p>Without this the grid would simply go blank for them - which would read as
 * "nobody is scheduled" rather than "everybody is on their usual shift", and
 * would be a worse lie than the one the cron job was telling.
 *
 * <p>The derived days are marked, so the UI can shade them differently from a
 * day somebody actually chose. That distinction matters on this screen more
 * than anywhere else: it is where HR decides what still needs assigning.
 */
@SpringBootTest
class PlannerDefaultedDaysTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final LocalDate FIRST_SUNDAY = LocalDate.of(2026, 9, 6);
    private static final LocalDate A_TUESDAY = LocalDate.of(2026, 9, 1);

    @Autowired private ShiftSchedulingService shiftSchedulingService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;

    private Company company;
    private Shift night;

    @BeforeEach
    void setUp() {
        clean();
        company = companyRepository.save(Company.builder()
                .companyCode("PLAN-CO").companyName("Planner Co").status(RecordStatus.ACTIVE).build());
        shiftRepository.save(shift("GENERAL", LocalTime.of(9, 0), LocalTime.of(18, 0)));
        night = shiftRepository.save(shift("NIGHT", LocalTime.of(18, 0), LocalTime.of(8, 0)));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("a permanent employee's month is filled in even with no roster rows")
    void plannerIsNotBlankForPermanentEmployees() {
        saveEmployee("PL001", Set.of(DayOfWeek.SUNDAY));

        MonthlyPlannerResponse.EmployeeRow row = rowFor("PL001");

        assertThat(row.shiftByDate()).hasSize(30);
        assertThat(row.shiftByDate().get(A_TUESDAY)).isEqualTo("GENERAL");
        assertThat(row.shiftByDate().get(FIRST_SUNDAY)).isEqualTo("WO");
    }

    @Test
    @DisplayName("the grid shows each employee's own weekly off")
    void gridShowsPerEmployeeWeeklyOff() {
        saveEmployee("PL002", Set.of(DayOfWeek.SUNDAY));
        saveEmployee("PL003", Set.of(DayOfWeek.TUESDAY));

        assertThat(rowFor("PL002").shiftByDate().get(FIRST_SUNDAY)).isEqualTo("WO");
        assertThat(rowFor("PL002").shiftByDate().get(A_TUESDAY)).isEqualTo("GENERAL");

        assertThat(rowFor("PL003").shiftByDate().get(A_TUESDAY)).isEqualTo("WO");
        assertThat(rowFor("PL003").shiftByDate().get(FIRST_SUNDAY)).isEqualTo("GENERAL");
    }

    @Test
    @DisplayName("derived days are flagged so the grid can tell them from assigned ones")
    void derivedDaysAreFlagged() {
        saveEmployee("PL004", Set.of(DayOfWeek.SUNDAY));
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("PL004").shiftDate(A_TUESDAY).shift(night)
                .weekOff(false).assignedBy("HR001").build());

        MonthlyPlannerResponse.EmployeeRow row = rowFor("PL004");

        assertThat(row.shiftByDate().get(A_TUESDAY)).isEqualTo("NIGHT");
        assertThat(row.defaultedByDate().get(A_TUESDAY)).isFalse();

        assertThat(row.shiftByDate().get(PERIOD.atDay(2))).isEqualTo("GENERAL");
        assertThat(row.defaultedByDate().get(PERIOD.atDay(2))).isTrue();
        assertThat(row.defaultedByDate().get(FIRST_SUNDAY)).isTrue();
    }

    @Test
    @DisplayName("an employee who is not auto-rostered still shows only what was assigned")
    void nonAutoRosteredEmployeeShowsOnlyAssignedDays() {
        Employee contract = saveEmployee("PL005", Set.of(DayOfWeek.SUNDAY));
        contract.setStatus(EmployeeStatus.CONTRACT);
        employeeRepository.saveAndFlush(contract);
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("PL005").shiftDate(A_TUESDAY).shift(night)
                .weekOff(false).assignedBy("HR001").build());

        MonthlyPlannerResponse.EmployeeRow row = rowFor("PL005");

        // The blanks are the point for them - they are the days still to plan.
        assertThat(row.shiftByDate()).hasSize(1);
        assertThat(row.shiftByDate().get(A_TUESDAY)).isEqualTo("NIGHT");
    }

    // ---- helpers -----------------------------------------------------------

    private MonthlyPlannerResponse.EmployeeRow rowFor(String userId) {
        return shiftSchedulingService.getMonthlyPlanner(PERIOD, null).rows().stream()
                .filter(row -> row.userId().equals(userId))
                .findFirst().orElseThrow();
    }

    private Shift shift(String code, LocalTime start, LocalTime end) {
        return Shift.builder().company(company).shiftCode(code).shiftName(code)
                .startTime(start).endTime(end)
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240)
                .build();
    }

    private Employee saveEmployee(String userId, Set<DayOfWeek> weekOffDays) {
        return employeeRepository.saveAndFlush(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .weekOffDays(weekOffDays)
                .grossSalary(new BigDecimal("30000")).pfBasic(new BigDecimal("15000"))
                .medicalAllowance(BigDecimal.ZERO).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false)
                .build());
    }

    private void clean() {
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        companyRepository.deleteAll();
    }
}
