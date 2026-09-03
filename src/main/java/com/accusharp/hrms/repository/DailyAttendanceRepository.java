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

    /**
     * Locked days on or after a date, for the employees given - the back-dating
     * guard on attendance policy rules.
     *
     * <p>A locked day is a paid day. A policy version that reaches back into one
     * would re-price a month somebody has already been paid for, silently, the
     * next time any report calls {@code syncSummaries}. Refusing the write is
     * cheaper than detecting the drift afterwards.
     */
    List<DailyAttendance> findTop50ByUserIdInAndAttendanceDateGreaterThanEqualAndLockedTrueOrderByAttendanceDateAsc(
            Collection<String> userIds, LocalDate fromDate);
}
