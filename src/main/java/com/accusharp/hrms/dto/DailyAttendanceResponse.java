package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.AttendanceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One day of computed attendance. Derived from raw punches interpreted through
 * the scheduled shift - never stored, always recomputed.
 */
public record DailyAttendanceResponse(
        String userId,
        LocalDate attendanceDate,
        String shiftCode,
        LocalDateTime firstIn,
        LocalDateTime lastOut,
        BigDecimal workingHours,
        BigDecimal breakHours,
        BigDecimal overtimeHours,
        int lateMinutes,
        int earlyExitMinutes,
        boolean invalidPunch,
        AttendanceStatus status
) {
}
