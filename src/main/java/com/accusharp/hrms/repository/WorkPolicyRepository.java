package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.WorkPolicy;
import com.accusharp.hrms.enums.RuleScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkPolicyRepository extends JpaRepository<WorkPolicy, Long> {

    /**
     * Every policy row that could apply to one company on or before {@code asOf},
     * shared fallbacks included - one query, ranked in memory by
     * {@code WorkPolicyResolver}, the same trade
     * {@code AttendancePolicyRuleRepository.findApplicable} makes.
     */
    @Query("""
            select p from WorkPolicy p
            where (p.company.id = :companyId or p.company is null)
              and p.effectiveFrom <= :asOf
            """)
    List<WorkPolicy> findApplicable(@Param("companyId") Long companyId, @Param("asOf") LocalDate asOf);

    /** Shared rows only - for a caller with no company in context. */
    @Query("""
            select p from WorkPolicy p
            where p.company is null
              and p.effectiveFrom <= :asOf
            """)
    List<WorkPolicy> findApplicableGlobal(@Param("asOf") LocalDate asOf);

    List<WorkPolicy> findAllByCompanyIdOrderByScopeAscVersionDesc(Long companyId);

    /** The current head of one chain, for assigning the next version number. */
    Optional<WorkPolicy> findFirstByCompanyIdAndScopeAndScopeRefOrderByVersionDesc(
            Long companyId, RuleScope scope, String scopeRef);
}
