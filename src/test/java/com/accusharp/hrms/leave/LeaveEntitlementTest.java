package com.accusharp.hrms.leave;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveRuleRepository;
import com.accusharp.hrms.service.leave.LeaveBalanceService;
import com.accusharp.hrms.service.leave.LeaveEntitlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who gets which leave, per employment type.
 *
 * <p>A rule says, for one leave type and one population, how the leave is
 * given: a yearly amount, earned from attendance, or not at all. The most
 * specific rule wins outright - an employment-type rule over the company-wide
 * one - and a rule only applies from its effective date.
 *
 * <p>The guarantee everything rests on: <b>with no rules configured, every
 * employee gets exactly what they got before this existed</b> - CL 12, SL 8 -
 * and nobody's balance moves until a company switches something on. And the
 * rest of the leave workflow is untouched: a type someone is not entitled to
 * simply has a zero balance, and the existing balance check refuses it.
 */
@SpringBootTest
class LeaveEntitlementTest {

    private static final int YEAR = 2026;

    @Autowired private LeaveBalanceService leaveBalanceService;
    @Autowired private LeaveEntitlementService leaveEntitlementService;
    @Autowired private LeaveRuleRepository leaveRuleRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CompanyRepository companyRepository;

    private Company company;

    @BeforeEach
    void setUp() {
        clean();
        company = companyRepository.save(Company.builder()
                .companyCode("ENTITLE-CO").companyName("Entitlement Co").status(RecordStatus.ACTIVE).build());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- the guarantee -----------------------------------------------------

    @Test
    @DisplayName("with no rules, balances are exactly today's - CL 12, SL 8, and EL starts at nothing")
    void noRulesKeepsTodaysQuotas() {
        employee("LE001", EmployeeStatus.PERMANENT, LocalDate.of(2022, 1, 1));

        assertThat(quota("LE001", LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("12");
        assertThat(quota("LE001", LeaveType.SICK_LEAVE)).isEqualByComparingTo("8");
        assertThat(quota("LE001", LeaveType.EARNED_LEAVE)).isEqualByComparingTo("0");
    }

    // ---- rules and precedence ----------------------------------------------

    @Test
    @DisplayName("a company-wide rule sets the yearly amount")
    void companyRuleSetsTheQuota() {
        employee("LE002", EmployeeStatus.PERMANENT, LocalDate.of(2022, 1, 1));
        rule(RuleScope.COMPANY, LeaveRule.ANY, LeaveType.CASUAL_LEAVE,
                LeaveGrant.YEARLY_GRANT, "10", LocalDate.of(2026, 1, 1));

        assertThat(quota("LE002", LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("an employment-type rule beats the company-wide one - day-wise get no CL, permanent get 10")
    void employmentTypeRuleBeatsCompanyRule() {
        employee("LE003", EmployeeStatus.PERMANENT, LocalDate.of(2022, 1, 1));
        employee("LE004", EmployeeStatus.DAY_WISE, LocalDate.of(2022, 1, 1));
        rule(RuleScope.COMPANY, LeaveRule.ANY, LeaveType.CASUAL_LEAVE,
                LeaveGrant.YEARLY_GRANT, "10", LocalDate.of(2026, 1, 1));
        rule(RuleScope.EMPLOYMENT_TYPE, "DAY_WISE", LeaveType.CASUAL_LEAVE,
                LeaveGrant.NOT_ENTITLED, null, LocalDate.of(2026, 1, 1));

        assertThat(quota("LE003", LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("10");
        assertThat(quota("LE004", LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("leave someone is not entitled to is refused by the existing balance check, unchanged")
    void notEntitledIsRefusedAsBefore() {
        employee("LE005", EmployeeStatus.DAY_WISE, LocalDate.of(2022, 1, 1));
        rule(RuleScope.EMPLOYMENT_TYPE, "DAY_WISE", LeaveType.CASUAL_LEAVE,
                LeaveGrant.NOT_ENTITLED, null, LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> leaveBalanceService.consume("LE005", YEAR, LeaveType.CASUAL_LEAVE, BigDecimal.ONE))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient CASUAL_LEAVE balance");
    }

    @Test
    @DisplayName("someone joining mid-year gets the yearly amount pro-rated to the months left, to the nearest half day")
    void midYearJoinerIsProRated() {
        rule(RuleScope.COMPANY, LeaveRule.ANY, LeaveType.CASUAL_LEAVE,
                LeaveGrant.YEARLY_GRANT, "12", LocalDate.of(2026, 1, 1));
        rule(RuleScope.COMPANY, LeaveRule.ANY, LeaveType.SICK_LEAVE,
                LeaveGrant.YEARLY_GRANT, "8", LocalDate.of(2026, 1, 1));
        employee("LE006", EmployeeStatus.PERMANENT, LocalDate.of(2026, 9, 1));
        employee("LE007", EmployeeStatus.PERMANENT, LocalDate.of(2026, 8, 10));

        // September joiner: 4 months of 12 -> 12 x 4/12 = 4.
        assertThat(quota("LE006", LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("4");
        // August joiner, joining month counted: 8 x 5/12 = 3.33 -> 3.5.
        assertThat(quota("LE007", LeaveType.SICK_LEAVE)).isEqualByComparingTo("3.5");
    }

    @Test
    @DisplayName("a rule effective from next year does not touch this year")
    void aFutureRuleDoesNotApplyYet() {
        employee("LE008", EmployeeStatus.PERMANENT, LocalDate.of(2022, 1, 1));
        rule(RuleScope.COMPANY, LeaveRule.ANY, LeaveType.CASUAL_LEAVE,
                LeaveGrant.YEARLY_GRANT, "10", LocalDate.of(2027, 1, 1));

        assertThat(quota("LE008", LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("a balance that already exists - entered by hand in the database - is never re-seeded")
    void existingBalanceIsNeverReseeded() {
        employee("LE009", EmployeeStatus.PERMANENT, LocalDate.of(2022, 1, 1));
        leaveBalanceRepository.save(LeaveBalance.builder()
                .userId("LE009").leaveYear(YEAR).leaveType(LeaveType.EARNED_LEAVE)
                .quota(new BigDecimal("14.5")).used(BigDecimal.ZERO).build());
        rule(RuleScope.COMPANY, LeaveRule.ANY, LeaveType.EARNED_LEAVE,
                LeaveGrant.EARNED_BY_ATTENDANCE, null, LocalDate.of(2026, 1, 1));

        // This is how the opening EL balances arrive: typed into the table.
        // A rule configured afterwards must not wipe them.
        assertThat(quota("LE009", LeaveType.EARNED_LEAVE)).isEqualByComparingTo("14.5");
    }

    @Test
    @DisplayName("earned leave under an attendance rule starts the year at nothing - it is credited month by month")
    void earnedLeaveStartsAtZero() {
        employee("LE010", EmployeeStatus.PERMANENT, LocalDate.of(2022, 1, 1));
        rule(RuleScope.COMPANY, LeaveRule.ANY, LeaveType.EARNED_LEAVE,
                LeaveGrant.EARNED_BY_ATTENDANCE, null, LocalDate.of(2026, 1, 1));

        assertThat(quota("LE010", LeaveType.EARNED_LEAVE)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a contractor's worker is entitled to nothing - their leave is their contractor's obligation")
    void contractorWorkerGetsNothing() {
        Employee worker = Employee.builder()
                .userId("LE011").status(EmployeeStatus.PERMANENT)
                .contractor(Contractor.builder().id(1L).build())
                .build();

        assertThat(leaveEntitlementService.openingQuota(worker, YEAR, LeaveType.CASUAL_LEAVE))
                .isEqualByComparingTo("0");
    }

    // ---- helpers -----------------------------------------------------------

    private BigDecimal quota(String userId, LeaveType type) {
        return leaveBalanceService.getOrCreate(userId, YEAR, type).getQuota();
    }

    private void rule(RuleScope scope, String scopeRef, LeaveType type, LeaveGrant grant,
                      String yearlyDays, LocalDate effectiveFrom) {
        leaveRuleRepository.save(LeaveRule.builder()
                .company(company).scope(scope).scopeRef(scopeRef).leaveType(type).grantMethod(grant)
                .yearlyDays(yearlyDays == null ? null : new BigDecimal(yearlyDays))
                .effectiveFrom(effectiveFrom).enabled(true)
                .build());
    }

    private void employee(String userId, EmployeeStatus status, LocalDate joined) {
        employeeRepository.saveAndFlush(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(status).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .joiningDate(joined)
                .overtimeEligible(false)
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }

    private void clean() {
        leaveBalanceRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();
    }
}
