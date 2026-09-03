package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.AttendancePolicyOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendancePolicyOutcomeRepository extends JpaRepository<AttendancePolicyOutcome, Long> {

    List<AttendancePolicyOutcome> findAllByUserIdAndMonth(String userId, String month);

    /** Every rebuild replaces the month's outcomes wholesale - never increments them. */
    void deleteByUserIdAndMonth(String userId, String month);
}
