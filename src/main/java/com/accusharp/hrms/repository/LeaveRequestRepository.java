package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.enums.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findAllByUserIdOrderByFromDateDesc(String userId);

    List<LeaveRequest> findAllByStatus(LeaveStatus status);

    List<LeaveRequest> findAllBySupervisorIdAndStatus(String supervisorId, LeaveStatus status);

    /** Any request whose range overlaps [fromDate, toDate]. */
    List<LeaveRequest> findAllByUserIdAndStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            String userId, List<LeaveStatus> statuses, LocalDate toDate, LocalDate fromDate);

    List<LeaveRequest> findAllByStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            List<LeaveStatus> statuses, LocalDate toDate, LocalDate fromDate);

    long countByStatus(LeaveStatus status);
}
