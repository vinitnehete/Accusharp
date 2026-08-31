package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.DailyAttendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DailyAttendanceRepository extends JpaRepository<DailyAttendance, Long> {

    Optional<DailyAttendance> findByUserIdAndAttendanceDate(String userId, LocalDate attendanceDate);

    List<DailyAttendance> findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            String userId, LocalDate fromDate, LocalDate toDate);

    /** Batched form of {@link #findByUserIdAndAttendanceDate} across many employees for one day. */
    List<DailyAttendance> findAllByUserIdInAndAttendanceDate(Collection<String> userIds, LocalDate attendanceDate);

    /**
     * Every stored day for a whole batch of employees over a window - one
     * query for a company-wide day-level report (the overtime register, the
     * attendance exception report) instead of the same
     * {@link #findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc}
     * repeated once per employee.
     */
    List<DailyAttendance> findAllByUserIdInAndAttendanceDateBetweenOrderByUserIdAscAttendanceDateAsc(
            Collection<String> userIds, LocalDate fromDate, LocalDate toDate);
}
