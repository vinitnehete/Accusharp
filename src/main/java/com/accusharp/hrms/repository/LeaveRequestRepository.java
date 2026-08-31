package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.enums.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findAllByUserIdOrderByFromDateDesc(String userId);

    List<LeaveRequest> findAllByStatus(LeaveStatus status);

    List<LeaveRequest> findAllByStatusIn(List<LeaveStatus> statuses);

    List<LeaveRequest> findAllBySupervisorIdAndStatus(String supervisorId, LeaveStatus status);

    /** Any request whose range overlaps [fromDate, toDate]. */
    List<LeaveRequest> findAllByUserIdAndStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            String userId, List<LeaveStatus> statuses, LocalDate toDate, LocalDate fromDate);

    /** Batched form of the above across many employees at once. */
    List<LeaveRequest> findAllByUserIdInAndStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            List<String> userIds, List<LeaveStatus> statuses, LocalDate toDate, LocalDate fromDate);

    List<LeaveRequest> findAllByStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            List<LeaveStatus> statuses, LocalDate toDate, LocalDate fromDate);

    /**
     * Every request of any status whose range overlaps the window, for a
     * batch of employees - what the leave transaction report lists. Unlike
     * the finders above it deliberately does not filter on status: a
     * transaction report that hid rejected and cancelled requests would not
     * be a transaction report.
     */
    List<LeaveRequest> findAllByUserIdInAndFromDateLessThanEqualAndToDateGreaterThanEqualOrderByFromDateDesc(
            Collection<String> userIds, LocalDate toDate, LocalDate fromDate);
}
