package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.AttendancePolicyApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AttendancePolicyApplicationRepository
        extends JpaRepository<AttendancePolicyApplication, Long> {

    List<AttendancePolicyApplication> findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            String userId, LocalDate fromDate, LocalDate toDate);

    List<AttendancePolicyApplication> findAllByUserIdAndAttendanceDate(String userId, LocalDate date);

    /**
     * Clears the days a generation run is about to rewrite, so the trace is
     * rebuilt from scratch with the days it explains rather than accumulating
     * one row per run. Scoped to the exact dates being written - a locked or
     * {@code MANUAL} day the run skipped keeps the trace it already had.
     */
    void deleteByUserIdAndAttendanceDateIn(String userId, List<LocalDate> dates);
}
