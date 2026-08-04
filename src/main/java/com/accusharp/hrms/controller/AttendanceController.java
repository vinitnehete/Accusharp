package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.service.attendance.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Attendance is read-only over the punches the biometric device writes - there
 * is deliberately no endpoint to create or edit a punch.
 */
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    @GetMapping("/{userId}")
    public List<DailyAttendanceResponse> getDaily(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return attendanceService.getDailyAttendance(userId, fromDate, toDate);
    }

    @GetMapping("/{userId}/monthly")
    public MonthlyAttendanceResponse getMonthly(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return attendanceService.getMonthlyAttendance(userId, month);
    }

    /** Recomputes the cached summaries payroll consumes. */
    @PostMapping("/summaries/refresh")
    public List<MonthlyAttendanceSummary> refreshSummaries(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return attendanceService.refreshAllSummaries(month);
    }
}
