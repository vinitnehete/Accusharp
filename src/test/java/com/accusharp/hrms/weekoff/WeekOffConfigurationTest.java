package com.accusharp.hrms.weekoff;

import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.EmploymentType;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.OvertimeBasis;
import com.accusharp.hrms.enums.PayBasis;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The weekly off, as a property of the employee rather than of a roster row.
 *
 * <p>A permanent employee always works the {@code GENERAL} shift, so the only
 * thing a roster row was ever telling us about them was which days they do not
 * work. That belongs on the employee - not repeated across ninety generated
 * rows a quarter, where it could only ever say {@code SUNDAY} because the
 * generator hardcoded it.
 *
 * <h2>Unset is not the same as none</h2>
 *
 * <p>{@code null} means nobody has configured this employee yet, and resolves
 * to Sunday - exactly what the deleted {@code DefaultRosterService} wrote for
 * everyone. That is what keeps every row already in a client's database
 * behaving on Monday the way it behaved on Friday. An <em>explicitly empty</em>
 * set is a different statement: this employee has no weekly off at all. The
 * two must not collapse into each other, or "works every day" would be
 * unsayable and adopting the field would silently change existing pay.
 */
@SpringBootTest
class WeekOffConfigurationTest {

    private static final LocalDate A_SUNDAY = LocalDate.of(2026, 6, 14);
    private static final LocalDate A_TUESDAY = LocalDate.of(2026, 6, 16);
    private static final LocalDate A_SATURDAY = LocalDate.of(2026, 6, 20);

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
    }

    // ---- storage -----------------------------------------------------------

    @Test
    @DisplayName("an employee nobody has configured reads back unset, and resolves to Sunday")
    void unsetResolvesToSunday() {
        save(employee("WO001").build());

        Employee stored = reload("WO001");

        assertThat(stored.getWeekOffDays()).isNull();
        assertThat(stored.effectiveWeekOffDays()).containsExactly(DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("an explicitly empty set means no weekly off at all, not Sunday")
    void explicitlyEmptyMeansNoWeeklyOff() {
        save(employee("WO002").weekOffDays(Set.of()).build());

        Employee stored = reload("WO002");

        assertThat(stored.getWeekOffDays()).isEmpty();
        assertThat(stored.effectiveWeekOffDays()).isEmpty();
        assertThat(stored.isWeekOffOn(A_SUNDAY)).isFalse();
    }

    @Test
    @DisplayName("several week-off days round-trip through the database")
    void multipleDaysRoundTrip() {
        save(employee("WO003").weekOffDays(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)).build());

        Employee stored = reload("WO003");

        assertThat(stored.getWeekOffDays())
                .containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("week-off days are stored as readable day names, not an opaque bitmask")
    void storedAsReadableDayNames() {
        save(employee("WO004").weekOffDays(Set.of(DayOfWeek.TUESDAY)).build());

        // Support reads this column directly when a client asks why somebody was
        // marked absent. A bitmask would make that a decoding exercise.
        assertThat(rawWeekOffColumn("WO004")).isEqualTo("TUESDAY");
    }

    // ---- resolution --------------------------------------------------------

    @Test
    @DisplayName("employees can each have a different weekly off")
    void differentEmployeesCanHaveDifferentWeeklyOffs() {
        save(employee("WO005").weekOffDays(Set.of(DayOfWeek.SUNDAY)).build());
        save(employee("WO006").weekOffDays(Set.of(DayOfWeek.TUESDAY)).build());

        Employee sundayOff = reload("WO005");
        Employee tuesdayOff = reload("WO006");

        assertThat(sundayOff.isWeekOffOn(A_SUNDAY)).isTrue();
        assertThat(sundayOff.isWeekOffOn(A_TUESDAY)).isFalse();

        assertThat(tuesdayOff.isWeekOffOn(A_TUESDAY)).isTrue();
        assertThat(tuesdayOff.isWeekOffOn(A_SUNDAY)).isFalse();
    }

    @Test
    @DisplayName("an employee with two week-off days is off on both")
    void bothWeekOffDaysAreHonoured() {
        save(employee("WO007").weekOffDays(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)).build());

        Employee stored = reload("WO007");

        assertThat(stored.isWeekOffOn(A_SATURDAY)).isTrue();
        assertThat(stored.isWeekOffOn(A_SUNDAY)).isTrue();
        assertThat(stored.isWeekOffOn(A_TUESDAY)).isFalse();
    }

    // ---- who gets the default GENERAL shift --------------------------------

    @Test
    @DisplayName("a permanent employee with no employment type falls back to auto-rostering")
    void permanentWithNoEmploymentTypeAutoRosters() {
        Employee permanent = employee("WO008").status(EmployeeStatus.PERMANENT).build();
        Employee contract = employee("WO009").status(EmployeeStatus.CONTRACT).build();

        assertThat(permanent.autoRostersDefaultShift()).isTrue();
        assertThat(contract.autoRostersDefaultShift()).isFalse();
    }

    @Test
    @DisplayName("the employment type's flag overrides the legacy status fallback in both directions")
    void employmentTypeFlagWins() {
        Employee optedIn = employee("WO010").status(EmployeeStatus.CONTRACT)
                .employmentType(employmentType(true)).build();
        Employee optedOut = employee("WO011").status(EmployeeStatus.PERMANENT)
                .employmentType(employmentType(false)).build();

        assertThat(optedIn.autoRostersDefaultShift()).isTrue();
        assertThat(optedOut.autoRostersDefaultShift()).isFalse();
    }

    @Test
    @DisplayName("a contractor's worker is never auto-rostered, whatever the flag says")
    void contractorWorkerNeverAutoRosters() {
        Employee worker = employee("WO012").status(EmployeeStatus.PERMANENT)
                .employmentType(employmentType(true))
                .contractor(Contractor.builder().id(1L).build())
                .build();

        // They are on site only for the days their contractor sends them.
        // Filling a default GENERAL roster would manufacture absent days, and
        // therefore an invoice dispute, for days nobody was expected.
        assertThat(worker.autoRostersDefaultShift()).isFalse();
    }

    // ---- which weekly off counts for tracking and unassigned days ------------

    @Test
    @DisplayName("an auto-rostered employee's configured weekly off includes the unset-means-Sunday fallback")
    void autoRosteredConfiguredWeekOffFallsBackToSunday() {
        Employee permanent = employee("WO013").build();

        assertThat(permanent.hasConfiguredWeekOffOn(A_SUNDAY)).isTrue();
        assertThat(permanent.hasConfiguredWeekOffOn(A_TUESDAY)).isFalse();
    }

    @Test
    @DisplayName("anyone else has a weekly off only once somebody sets one")
    void othersHaveNoWeeklyOffUntilConfigured() {
        Employee unset = employee("WO014").status(EmployeeStatus.DAY_WISE).build();
        Employee tuesday = employee("WO015").status(EmployeeStatus.DAY_WISE)
                .weekOffDays(Set.of(DayOfWeek.TUESDAY)).build();

        // No Sunday fallback: the deleted cron never rostered day-wise staff, so
        // there is no existing behaviour to preserve and every reason not to
        // invent a day off nobody agreed to.
        assertThat(unset.hasConfiguredWeekOffOn(A_SUNDAY)).isFalse();
        assertThat(tuesday.hasConfiguredWeekOffOn(A_TUESDAY)).isTrue();
        assertThat(tuesday.hasConfiguredWeekOffOn(A_SUNDAY)).isFalse();
    }

    // ---- helpers -----------------------------------------------------------

    private Employee.EmployeeBuilder employee(String userId) {
        return Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName("Week Off " + userId)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .joiningDate(LocalDate.of(2023, 1, 1))
                .grossSalary(new BigDecimal("30000")).pfBasic(new BigDecimal("15000"))
                .medicalAllowance(BigDecimal.ZERO).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false);
    }

    private EmploymentType employmentType(boolean autoRoster) {
        return EmploymentType.builder()
                .typeCode("T" + autoRoster).typeName("Type")
                .payBasis(PayBasis.PER_CALENDAR_DAY_LESS_LOP)
                .overtimeBasis(OvertimeBasis.PER_DAY_SHIFT_EXCESS)
                .autoRosterDefaultShift(autoRoster)
                .build();
    }

    private void save(Employee employee) {
        employeeRepository.saveAndFlush(employee);
    }

    private Employee reload(String userId) {
        return employeeRepository.findByUserId(userId).orElseThrow();
    }

    private String rawWeekOffColumn(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT week_off_days FROM employee WHERE user_id = ?", String.class, userId);
    }
}
