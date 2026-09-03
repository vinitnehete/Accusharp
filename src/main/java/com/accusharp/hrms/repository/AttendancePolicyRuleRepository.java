package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendancePolicyRuleRepository extends JpaRepository<AttendancePolicyRule, Long> {

    /**
     * Every rule row that could apply to one company on or before {@code asOf},
     * global fallbacks included - one query per company per generation run,
     * filtered and ranked in memory by {@code AttendancePolicyResolver}.
     *
     * <p>Fetched whole rather than per employee and per type on purpose. A
     * month's generation resolves seven rule types for every employee on every
     * day; issuing a query for each would be tens of thousands of round trips
     * for a table that holds a handful of rows per company. This is the same
     * trade {@code AttendanceService.resolveCompanyContext} already makes for
     * the attendance rule and the holiday calendar.
     */
    @Query("""
            select r from AttendancePolicyRule r
            where (r.company.id = :companyId or r.company is null)
              and r.effectiveFrom <= :asOf
            """)
    List<AttendancePolicyRule> findApplicable(@Param("companyId") Long companyId,
                                             @Param("asOf") LocalDate asOf);

    /** Global rows only - for a caller with no company in context. */
    @Query("""
            select r from AttendancePolicyRule r
            where r.company is null
              and r.effectiveFrom <= :asOf
            """)
    List<AttendancePolicyRule> findApplicableGlobal(@Param("asOf") LocalDate asOf);

    /** Everything one company owns, newest version first - the management read. */
    List<AttendancePolicyRule> findAllByCompanyIdOrderByRuleTypeAscScopeAscVersionDesc(Long companyId);

    List<AttendancePolicyRule> findAllByCompanyIsNullOrderByRuleTypeAscScopeAscVersionDesc();

    /** The current head of one chain, for assigning the next version number. */
    Optional<AttendancePolicyRule> findFirstByCompanyIdAndScopeAndScopeRefAndRuleTypeOrderByVersionDesc(
            Long companyId, RuleScope scope, String scopeRef, RuleType ruleType);

    Optional<AttendancePolicyRule> findFirstByCompanyIsNullAndScopeAndScopeRefAndRuleTypeOrderByVersionDesc(
            RuleScope scope, String scopeRef, RuleType ruleType);
}
