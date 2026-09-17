package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.enums.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.math.BigDecimal;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    Optional<LeaveBalance> findByUserIdAndLeaveYearAndLeaveType(String userId, int leaveYear, LeaveType leaveType);

    List<LeaveBalance> findAllByLeaveYear(int leaveYear);

    /** Whether any of these employees has used leave - see {@code LeaveSettingsService}. */
    boolean existsByUserIdInAndUsedGreaterThan(Collection<String> userIds, BigDecimal used);
}
