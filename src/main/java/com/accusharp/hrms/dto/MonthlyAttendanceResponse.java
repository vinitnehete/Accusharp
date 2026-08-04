package com.accusharp.hrms.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public record MonthlyAttendanceResponse(
        String userId,
        String employeeName,
        YearMonth month,
        long workingDays,
        BigDecimal presentDays,
        BigDecimal absentDays,
        long halfDays,
        BigDecimal leaveDays,
        long holidayDays,
        long weekOffDays,
        long lateCount,
        long earlyExitCount,
        long invalidPunches,
        BigDecimal totalHours,
        BigDecimal overtimeHours,
        BigDecimal lopDays,
        List<DailyAttendanceResponse> days
) {
}
