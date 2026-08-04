package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonthlyAttendanceSummaryRepository extends JpaRepository<MonthlyAttendanceSummary, Long> {

    Optional<MonthlyAttendanceSummary> findByUserIdAndMonth(String userId, String month);

    List<MonthlyAttendanceSummary> findAllByMonth(String month);
}
