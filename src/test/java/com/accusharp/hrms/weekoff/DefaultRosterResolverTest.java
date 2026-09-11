package com.accusharp.hrms.weekoff;

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
import com.accusharp.hrms.service.shift.DefaultRosterResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roster an employee has without anybody writing one down.
 *
 * <p>A permanent employee always works {@code GENERAL}, so the roster row that
 * used to be generated for them ninety days at a time carried no information
 * that the employee record did not already have. This resolver derives the
 * same days on demand instead - which means they never run out, never disagree
 * with the employee's configured week-off, and cost nothing to store.
 *
 * <p>The days it produces are <b>transient</b>: they are never persisted, so
 * changing an employee's weekly off changes every day that has not been
 * explicitly overridden, retroactively and at once.
 */
@SpringBootTest
class DefaultRosterResolverTest {

    // A Monday, so the week runs Mon 15th .. Sun 21st.
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 15);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 6, 20);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 6, 21);

    @Autowired private DefaultRosterResolver resolver;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private CompanyRepository companyRepository;

    private Company company;
    private Shift night;

    @BeforeEach
    void setUp() {
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        companyRepository.deleteAll();

        company = companyRepository.save(Company.builder()
                .companyCode("RES").companyName("Resolver Co").status(RecordStatus.ACTIVE).build());
        shiftRepository.save(shift("GENERAL", LocalTime.of(9, 0), LocalTime.of(19, 0)));
        night = shiftRepository.save(shift("NIGHT", LocalTime.of(18, 0), LocalTime.of(8, 0)));
    }

    // ---- the default day ---------------------------------------------------

    @Test
    @DisplayName("every unrostered day of a permanent employee's week becomes a GENERAL day")
    void fillsTheWeekWithGeneral() {
        Employee employee = save(permanent("DR001").weekOffDays(Set.of(DayOfWeek.SUNDAY)));

        List<ShiftSchedule> week = resolver.merge(employee, List.of(), MONDAY, SUNDAY);

        assertThat(week).hasSize(7);
        assertThat(week).extracting(s -> s.getShift().getShiftCode())
                .containsOnly("GENERAL");
        assertThat(week).extracting(ShiftSchedule::getShiftDate)
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    @DisplayName("the employee's own week-off days are the days marked off")
    void marksTheEmployeesOwnWeekOffDays() {
        Employee sundayOff = save(permanent("DR002").weekOffDays(Set.of(DayOfWeek.SUNDAY)));
        Employee tuesdayOff = save(permanent("DR003").weekOffDays(Set.of(DayOfWeek.TUESDAY)));

        assertThat(weekOffDatesIn(resolver.merge(sundayOff, List.of(), MONDAY, SUNDAY)))
                .containsExactly(SUNDAY);
        assertThat(weekOffDatesIn(resolver.merge(tuesdayOff, List.of(), MONDAY, SUNDAY)))
                .containsExactly(MONDAY.plusDays(1));
    }

    @Test
    @DisplayName("an employee with a two-day weekend is off on both days")
    void marksTwoWeekOffDays() {
        Employee employee = save(permanent("DR004")
                .weekOffDays(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)));

        assertThat(weekOffDatesIn(resolver.merge(employee, List.of(), MONDAY, SUNDAY)))
                .containsExactly(SATURDAY, SUNDAY);
    }

    @Test
    @DisplayName("an employee with no weekly off configured falls back to Sunday")
    void unconfiguredFallsBackToSunday() {
        // Every row in an existing database looks like this. It has to keep
        // behaving the way the deleted generator behaved.
        Employee employee = save(permanent("DR005"));

        assertThat(weekOffDatesIn(resolver.merge(employee, List.of(), MONDAY, SUNDAY)))
                .containsExactly(SUNDAY);
    }

    @Test
    @DisplayName("an employee with an explicitly empty week-off works all seven days")
    void explicitlyNoWeeklyOff() {
        Employee employee = save(permanent("DR006").weekOffDays(Set.of()));

        assertThat(weekOffDatesIn(resolver.merge(employee, List.of(), MONDAY, SUNDAY))).isEmpty();
    }

    // ---- explicit rows win -------------------------------------------------

    @Test
    @DisplayName("a roster row somebody actually assigned beats the default")
    void explicitRowWins() {
        Employee employee = save(permanent("DR007").weekOffDays(Set.of(DayOfWeek.SUNDAY)));
        ShiftSchedule assigned = shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("DR007").shiftDate(MONDAY.plusDays(2)).shift(night)
                .weekOff(false).assignedBy("HR001").build());

        List<ShiftSchedule> week = resolver.merge(employee, List.of(assigned), MONDAY, SUNDAY);

        assertThat(week).hasSize(7);
        assertThat(dayOn(week, MONDAY.plusDays(2)).getShift().getShiftCode()).isEqualTo("NIGHT");
        assertThat(dayOn(week, MONDAY.plusDays(2)).getAssignedBy()).isEqualTo("HR001");
        // Its neighbours are untouched.
        assertThat(dayOn(week, MONDAY.plusDays(1)).getShift().getShiftCode()).isEqualTo("GENERAL");
        assertThat(dayOn(week, MONDAY.plusDays(3)).getShift().getShiftCode()).isEqualTo("GENERAL");
    }

    @Test
    @DisplayName("HR can override a default week-off into a working day")
    void explicitRowCanOverrideAWeekOff() {
        Employee employee = save(permanent("DR008").weekOffDays(Set.of(DayOfWeek.SUNDAY)));
        ShiftSchedule sundayWorking = shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("DR008").shiftDate(SUNDAY).shift(night)
                .weekOff(false).assignedBy("HR001").build());

        List<ShiftSchedule> week = resolver.merge(employee, List.of(sundayWorking), MONDAY, SUNDAY);

        assertThat(dayOn(week, SUNDAY).isWeekOff()).isFalse();
        assertThat(weekOffDatesIn(week)).isEmpty();
    }

    // ---- what it must not touch --------------------------------------------

    @Test
    @DisplayName("an employee who is not auto-rostered gets only the rows that exist")
    void nonAutoRosteredEmployeeIsLeftAlone() {
        // Contract and day-wise staff are rostered sparsely on purpose - an
        // unrostered day genuinely means "not a working day" for them, and
        // filling it would invent loss of pay.
        Employee contract = save(permanent("DR009").status(EmployeeStatus.CONTRACT));

        assertThat(resolver.merge(contract, List.of(), MONDAY, SUNDAY)).isEmpty();
    }

    @Test
    @DisplayName("defaults stop at the joining date")
    void respectsJoiningDate() {
        Employee employee = save(permanent("DR010")
                .weekOffDays(Set.of())
                .joiningDate(MONDAY.plusDays(3)));

        assertThat(resolver.merge(employee, List.of(), MONDAY, SUNDAY))
                .extracting(ShiftSchedule::getShiftDate)
                .containsExactly(MONDAY.plusDays(3), MONDAY.plusDays(4),
                        MONDAY.plusDays(5), MONDAY.plusDays(6));
    }

    @Test
    @DisplayName("defaults stop at the relieving date")
    void respectsRelievingDate() {
        Employee employee = save(permanent("DR011")
                .weekOffDays(Set.of())
                .relievingDate(MONDAY.plusDays(2)));

        // Nobody is absent after they have left, and payroll already caps
        // payable days by the same window.
        assertThat(resolver.merge(employee, List.of(), MONDAY, SUNDAY))
                .extracting(ShiftSchedule::getShiftDate)
                .containsExactly(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2));
    }

    @Test
    @DisplayName("a company with no GENERAL shift falls back to the rows it has, without failing")
    void missingGeneralShiftIsNotAnError() {
        shiftRepository.deleteAll();
        Employee employee = save(permanent("DR012"));

        // Best-effort, exactly like the generator it replaces: an unseeded
        // environment or a renamed shift must not break attendance generation.
        assertThat(resolver.merge(employee, List.of(), MONDAY, SUNDAY)).isEmpty();
    }

    // ---- the days are derived, never stored --------------------------------

    @Test
    @DisplayName("defaulted days are transient and flagged as defaulted")
    void defaultedDaysAreTransient() {
        Employee employee = save(permanent("DR013").weekOffDays(Set.of(DayOfWeek.SUNDAY)));
        ShiftSchedule assigned = shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("DR013").shiftDate(MONDAY).shift(night)
                .weekOff(false).assignedBy("HR001").build());

        List<ShiftSchedule> week = resolver.merge(employee, List.of(assigned), MONDAY, SUNDAY);

        assertThat(dayOn(week, MONDAY).getId()).isNotNull();
        assertThat(dayOn(week, MONDAY).isDefaulted()).isFalse();

        assertThat(dayOn(week, SUNDAY).getId()).isNull();
        assertThat(dayOn(week, SUNDAY).isDefaulted()).isTrue();

        // Nothing new reached the table.
        assertThat(shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc("DR013")).hasSize(1);
    }

    // ---- helpers -----------------------------------------------------------

    private List<LocalDate> weekOffDatesIn(List<ShiftSchedule> days) {
        return days.stream().filter(ShiftSchedule::isWeekOff).map(ShiftSchedule::getShiftDate).toList();
    }

    private ShiftSchedule dayOn(List<ShiftSchedule> days, LocalDate date) {
        return days.stream().filter(day -> day.getShiftDate().equals(date)).findFirst().orElseThrow();
    }

    private Shift shift(String code, LocalTime start, LocalTime end) {
        return Shift.builder().shiftCode(code).shiftName(code).startTime(start).endTime(end)
                .workingHours(8).breakMinutes(0).graceMinutes(120).overtimeWindowMinutes(240)
                .build();
    }

    private Employee.EmployeeBuilder permanent(String userId) {
        return Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName("Resolver " + userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .joiningDate(LocalDate.of(2023, 1, 1))
                .grossSalary(new BigDecimal("30000")).pfBasic(new BigDecimal("15000"))
                .medicalAllowance(BigDecimal.ZERO).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false);
    }

    private Employee save(Employee.EmployeeBuilder builder) {
        return employeeRepository.saveAndFlush(builder.build());
    }
}
