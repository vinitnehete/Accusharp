package com.accusharp.hrms.policy;

import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.Category;
import com.accusharp.hrms.entity.Department;
import com.accusharp.hrms.entity.Designation;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.service.policy.AttendancePolicyResolver;
import com.accusharp.hrms.service.policy.ResolvedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolution is the part of the policy engine most likely to be quietly wrong,
 * because a mistake here does not throw - it applies somebody else's rule and
 * pays the wrong amount. These are plain unit tests with no Spring context: the
 * resolver takes the rule list as an argument precisely so it can be tested
 * exhaustively without a database.
 *
 * <p>The scenario mirrors the worked collision in
 * {@code docs/design/attendance-policy-engine.md} section 4.
 */
class AttendancePolicyResolverTest {

    private static final LocalDate SEP_14 = LocalDate.of(2026, 9, 14);

    private final AttendancePolicyResolver resolver = new AttendancePolicyResolver(null);

    private final Employee employee = employee("SE10012", "STAFF", "OPS", "ENGINEER", EmployeeStatus.PERMANENT);

    @Test
    @DisplayName("no rules at all resolves to NONE - the state every unconfigured company is in, and the short-circuit the whole no-op guarantee rests on")
    void emptyRuleSetResolvesToNone() {
        ResolvedPolicy policy = resolver.resolve(List.of(), employee, SEP_14);

        assertThat(policy).isSameAs(ResolvedPolicy.NONE);
        assertThat(policy.isEmpty()).isTrue();
        assertThat(policy.hasAnyDayRule()).isFalse();
        assertThat(policy.hasAnyMonthRule()).isFalse();
    }

    @Test
    @DisplayName("the design doc's worked collision: category beats department beats company, and the version in force on the date wins within the category")
    void mostSpecificScopeAndVersionInForceBothWin() {
        AttendancePolicyRule company = rule(RuleScope.COMPANY, RuleScope.ANY, 1, "2026-01-01", true, 5);
        AttendancePolicyRule department = rule(RuleScope.DEPARTMENT, "OPS", 1, "2026-04-01", true, 20);
        AttendancePolicyRule categoryV1 = rule(RuleScope.CATEGORY, "STAFF", 1, "2026-06-01", true, 10);
        AttendancePolicyRule categoryV2 = rule(RuleScope.CATEGORY, "STAFF", 2, "2026-09-01", true, 15);
        AttendancePolicyRule categoryV3 = rule(RuleScope.CATEGORY, "STAFF", 3, "2026-10-01", true, 30);
        AttendancePolicyRule otherDesignation = rule(RuleScope.DESIGNATION, "TEAM-LEAD", 1, "2026-02-01", true, 0);

        List<AttendancePolicyRule> all =
                List.of(company, department, categoryV1, categoryV2, categoryV3, otherDesignation);

        // 14 Sep: v3 has not started, TEAM-LEAD is not this employee's designation.
        assertThat(resolver.resolve(all, employee, SEP_14).rule(RuleType.LATE_ARRIVAL))
                .contains(categoryV2);

        // Same rules, earlier date: the previous category version.
        assertThat(resolver.resolve(all, employee, LocalDate.of(2026, 8, 20)).rule(RuleType.LATE_ARRIVAL))
                .contains(categoryV1);

        // Same rules, later date: the version that had not started yet.
        assertThat(resolver.resolve(all, employee, LocalDate.of(2026, 10, 5)).rule(RuleType.LATE_ARRIVAL))
                .contains(categoryV3);
    }

    @Test
    @DisplayName("a version starting exactly on the attendance date is already in force - the boundary is inclusive")
    void effectiveFromIsInclusiveOnTheDayItself() {
        AttendancePolicyRule v1 = rule(RuleScope.COMPANY, RuleScope.ANY, 1, "2026-01-01", true, 5);
        AttendancePolicyRule v2 = rule(RuleScope.COMPANY, RuleScope.ANY, 2, "2026-09-14", true, 15);

        assertThat(resolver.resolve(List.of(v1, v2), employee, LocalDate.of(2026, 9, 13)).rule(RuleType.LATE_ARRIVAL))
                .contains(v1);
        assertThat(resolver.resolve(List.of(v1, v2), employee, SEP_14).rule(RuleType.LATE_ARRIVAL))
                .contains(v2);
    }

    @Test
    @DisplayName("a disabled specific rule beats an enabled general one and resolves to nothing - it must not fall through to the company rule")
    void disabledSpecificRuleDoesNotFallThroughToBroaderScope() {
        AttendancePolicyRule company = rule(RuleScope.COMPANY, RuleScope.ANY, 1, "2026-01-01", true, 5);
        AttendancePolicyRule managersOptOut = rule(RuleScope.CATEGORY, "MANAGER", 1, "2026-01-01", false, 0);

        Employee manager = employee("MGR001", "MANAGER", "OPS", "ENGINEER", EmployeeStatus.PERMANENT);
        Employee staff = employee("STF001", "STAFF", "OPS", "ENGINEER", EmployeeStatus.PERMANENT);

        List<AttendancePolicyRule> all = List.of(company, managersOptOut);

        // The manager's disabled category rule wins, and applying nothing is the
        // answer. Falling through to the company rule would invert the opt-out.
        assertThat(resolver.resolve(all, manager, SEP_14).rule(RuleType.LATE_ARRIVAL)).isEmpty();
        assertThat(resolver.resolve(all, manager, SEP_14).isEmpty()).isTrue();

        // Everyone else still gets the company rule.
        assertThat(resolver.resolve(all, staff, SEP_14).rule(RuleType.LATE_ARRIVAL)).contains(company);
    }

    @Test
    @DisplayName("employee scope outranks every other, including a later-dated category version")
    void employeeScopeOutranksEverything() {
        AttendancePolicyRule company = rule(RuleScope.COMPANY, RuleScope.ANY, 1, "2026-01-01", true, 5);
        AttendancePolicyRule category = rule(RuleScope.CATEGORY, "STAFF", 1, "2026-09-10", true, 20);
        AttendancePolicyRule individual = rule(RuleScope.EMPLOYEE, "SE10012", 1, "2026-01-01", true, 45);

        assertThat(resolver.resolve(List.of(company, category, individual), employee, SEP_14)
                .rule(RuleType.LATE_ARRIVAL))
                .contains(individual);
    }

    @Test
    @DisplayName("each rule type resolves independently - a category LATE_ARRIVAL does not suppress a company OVERTIME")
    void ruleTypesResolveIndependently() {
        AttendancePolicyRule lateAtCategory = rule(RuleScope.CATEGORY, "STAFF", 1, "2026-01-01", true, 15);
        AttendancePolicyRule overtimeAtCompany = ruleOf(RuleType.OVERTIME, RuleScope.COMPANY, RuleScope.ANY,
                1, "2026-01-01", true, "{\"payable\":true,\"minimumMinutes\":30,"
                        + "\"roundingBlockMinutes\":30,\"rounding\":\"DOWN\"}");

        ResolvedPolicy policy = resolver.resolve(List.of(lateAtCategory, overtimeAtCompany), employee, SEP_14);

        assertThat(policy.rule(RuleType.LATE_ARRIVAL)).contains(lateAtCategory);
        assertThat(policy.rule(RuleType.OVERTIME)).contains(overtimeAtCompany);
        assertThat(policy.rule(RuleType.SHORT_HOURS)).isEmpty();
        assertThat(policy.hasAnyDayRule()).isTrue();
        assertThat(policy.hasAnyMonthRule()).isFalse();
    }

    @Test
    @DisplayName("employment type is a scope like any other, so DAY_WISE and PERMANENT can carry different policy")
    void employmentTypeIsAScope() {
        AttendancePolicyRule dayWiseRule =
                rule(RuleScope.EMPLOYMENT_TYPE, EmployeeStatus.DAY_WISE.name(), 1, "2026-01-01", true, 0);
        AttendancePolicyRule company = rule(RuleScope.COMPANY, RuleScope.ANY, 1, "2026-01-01", true, 15);

        Employee dayWise = employee("DW001", "STAFF", "OPS", "ENGINEER", EmployeeStatus.DAY_WISE);

        assertThat(resolver.resolve(List.of(dayWiseRule, company), dayWise, SEP_14).rule(RuleType.LATE_ARRIVAL))
                .contains(dayWiseRule);
        assertThat(resolver.resolve(List.of(dayWiseRule, company), employee, SEP_14).rule(RuleType.LATE_ARRIVAL))
                .contains(company);
    }

    @Test
    @DisplayName("an employee with no category, department or designation still resolves company and employment-type rules")
    void employeeWithNoMastersStillResolves() {
        Employee bare = Employee.builder()
                .userId("BARE001").status(EmployeeStatus.CONTRACT).build();

        AttendancePolicyRule category = rule(RuleScope.CATEGORY, "STAFF", 1, "2026-01-01", true, 20);
        AttendancePolicyRule company = rule(RuleScope.COMPANY, RuleScope.ANY, 1, "2026-01-01", true, 15);

        // The category rule cannot match an employee who has no category, and
        // must not throw trying.
        assertThat(resolver.resolve(List.of(category, company), bare, SEP_14).rule(RuleType.LATE_ARRIVAL))
                .contains(company);
    }

    @Test
    @DisplayName("candidatesFor lists the beaten rules too, so the explain endpoint can answer 'why isn't my department rule applying'")
    void candidatesAreRankedMostSpecificFirst() {
        AttendancePolicyRule company = rule(RuleScope.COMPANY, RuleScope.ANY, 1, "2026-01-01", true, 5);
        AttendancePolicyRule department = rule(RuleScope.DEPARTMENT, "OPS", 1, "2026-04-01", true, 20);
        AttendancePolicyRule categoryV2 = rule(RuleScope.CATEGORY, "STAFF", 2, "2026-09-01", true, 15);
        AttendancePolicyRule future = rule(RuleScope.CATEGORY, "STAFF", 3, "2026-10-01", true, 30);

        List<AttendancePolicyRule> candidates = resolver.candidatesFor(
                List.of(company, future, department, categoryV2), employee, SEP_14, RuleType.LATE_ARRIVAL);

        // Future version excluded; the rest ranked most specific first.
        assertThat(candidates).containsExactly(categoryV2, department, company);
    }

    // ---- helpers -----------------------------------------------------------

    private AttendancePolicyRule rule(RuleScope scope, String scopeRef, int version,
                                      String effectiveFrom, boolean enabled, int graceMinutes) {
        return ruleOf(RuleType.LATE_ARRIVAL, scope, scopeRef, version, effectiveFrom, enabled,
                "{\"graceMinutes\":" + graceMinutes + ",\"penaltyStatus\":\"HALF_DAY\"}");
    }

    private AttendancePolicyRule ruleOf(RuleType ruleType, RuleScope scope, String scopeRef, int version,
                                        String effectiveFrom, boolean enabled, String params) {
        return AttendancePolicyRule.builder()
                .id((long) System.identityHashCode(scope.name() + scopeRef + version + ruleType))
                .scope(scope).scopeRef(scopeRef).ruleType(ruleType).version(version)
                .effectiveFrom(LocalDate.parse(effectiveFrom)).enabled(enabled).params(params)
                .build();
    }

    private Employee employee(String userId, String categoryCode, String departmentCode,
                              String designationCode, EmployeeStatus status) {
        return Employee.builder()
                .userId(userId)
                .status(status)
                .category(Category.builder().categoryCode(categoryCode).build())
                .department(Department.builder().departmentCode(departmentCode).build())
                .designation(Designation.builder().designationCode(designationCode).build())
                .build();
    }
}
