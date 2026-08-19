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

    List<DailyAttendance> findAllByAttendanceDateBetween(LocalDate fromDate, LocalDate toDate);

    /** Batched form of {@link #findByUserIdAndAttendanceDate} across many employees for one day. */
    List<DailyAttendance> findAllByUserIdInAndAttendanceDate(Collection<String> userIds, LocalDate attendanceDate);

    long countByUserIdAndAttendanceDateBetween(String userId, LocalDate fromDate, LocalDate toDate);
}
