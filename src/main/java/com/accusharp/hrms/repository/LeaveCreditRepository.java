package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.LeaveCredit;
import com.accusharp.hrms.enums.LeaveCreditKind;
import com.accusharp.hrms.enums.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface LeaveCreditRepository extends JpaRepository<LeaveCredit, Long> {

    Optional<LeaveCredit> findByUserIdAndLeaveTypeAndKindAndPeriod(
            String userId, LeaveType leaveType, LeaveCreditKind kind, String period);

    List<LeaveCredit> findAllByUserIdAndLeaveYearOrderByPeriodAsc(String userId, int leaveYear);

    /** One year's accruals of one type - what a yearly accrual cap is measured against. */
    List<LeaveCredit> findAllByUserIdAndLeaveTypeAndKindAndLeaveYear(
            String userId, LeaveType leaveType, LeaveCreditKind kind, int leaveYear);

    boolean existsByUserIdIn(Collection<String> userIds);
}
