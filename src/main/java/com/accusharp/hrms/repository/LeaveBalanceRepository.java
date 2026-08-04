package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.enums.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    Optional<LeaveBalance> findByUserIdAndLeaveYearAndLeaveType(String userId, int leaveYear, LeaveType leaveType);

    List<LeaveBalance> findAllByUserIdAndLeaveYear(String userId, int leaveYear);

    List<LeaveBalance> findAllByLeaveYear(int leaveYear);
}
