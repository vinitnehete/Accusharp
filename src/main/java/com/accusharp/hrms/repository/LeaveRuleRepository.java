package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.enums.LeaveRuleScope;
import com.accusharp.hrms.enums.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface LeaveRuleRepository extends JpaRepository<LeaveRule, Long> {

    List<LeaveRule> findAllByCompanyIdAndLeaveTypeAndEnabledTrue(Long companyId, LeaveType leaveType);

    List<LeaveRule> findAllByCompanyIsNullAndLeaveTypeAndEnabledTrue(LeaveType leaveType);

    List<LeaveRule> findAllByCompanyIdOrderByLeaveTypeAscEffectiveFromDesc(Long companyId);

    List<LeaveRule> findAllByCompanyIsNullOrderByLeaveTypeAscEffectiveFromDesc();

    boolean existsByCompanyIdAndScopeAndScopeRefAndLeaveTypeAndEffectiveFrom(
            Long companyId, LeaveRuleScope scope, String scopeRef, LeaveType leaveType, LocalDate effectiveFrom);

    boolean existsByCompanyIsNullAndScopeAndScopeRefAndLeaveTypeAndEffectiveFrom(
            LeaveRuleScope scope, String scopeRef, LeaveType leaveType, LocalDate effectiveFrom);
}
