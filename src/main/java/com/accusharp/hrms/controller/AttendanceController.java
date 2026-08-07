package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.AttendanceCorrectionRequest;
import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.attendance.AttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Punches themselves remain read-only - the device owns {@code device_logs} and
 * there is still no endpoint to create or edit one. What an admin can change is
 * the <em>generated attendance</em>: correcting a day records the correction
 * alongside the original device reading rather than rewriting history.
 *
 * <p>{@code generatedBy}/{@code updatedBy}/{@code actorId} are overwritten
 * with the caller's own authenticated username below, never trusted from the
 * request - {@code AttendanceService} uses them to enforce the HR/ADMIN-only
 * rule on generate/correct/unlock.
 */
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    /** Generates the stored attendance for a period - one employee, several, or all. */
    @PreAuthorize("@authz.can('ATTENDANCE_GENERATE')")
    @PostMapping("/generate")
    public AttendanceGenerationResponse generate(@AuthenticationPrincipal UserPrincipal principal,
                                                  @Valid @RequestBody AttendanceGenerationRequest request) {
        request.setGeneratedBy(principal.getUsername());
        return attendanceService.generate(request);
    }

    /** The stored rows for a month, provenance included. */
    @PreAuthorize("@authz.can('ATTENDANCE_READ')")
    @GetMapping("/{userId}/records")
    public List<AttendanceRecordResponse> getRecords(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return attendanceService.getRecords(userId, month);
    }

    /** Corrects one generated day; the row becomes MANUAL. */
    @PreAuthorize("@authz.can('ATTENDANCE_CORRECT')")
    @PutMapping("/{userId}/{date}")
    public AttendanceRecordResponse correctDay(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String userId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody AttendanceCorrectionRequest request) {
        request.setUpdatedBy(principal.getUsername());
        return attendanceService.correctDay(userId, date, request);
    }

    /** Reopens a month frozen by payroll. Regenerate payroll afterwards. */
    @PreAuthorize("@authz.can('ATTENDANCE_UNLOCK')")
    @PostMapping("/{userId}/unlock")
    public Map<String, Object> unlock(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return Map.of("userId", userId, "month", month.toString(),
                "unlockedDays", attendanceService.unlockMonth(userId, month, principal.getUsername()));
    }

    @PreAuthorize("@authz.can('ATTENDANCE_READ')")
    @GetMapping("/{userId}")
    public List<DailyAttendanceResponse> getDaily(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return attendanceService.getDailyAttendance(userId, fromDate, toDate);
    }

    /** Stored attendance once generated; otherwise a preview that persists nothing. */
    @PreAuthorize("@authz.can('ATTENDANCE_READ')")
    @GetMapping("/{userId}/monthly")
    public MonthlyAttendanceResponse getMonthly(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return attendanceService.getMonthlyAttendance(userId, month);
    }

    /** Resyncs the cached summaries from the stored days. */
    @PreAuthorize("@authz.can('ATTENDANCE_GENERATE')")
    @PostMapping("/summaries/refresh")
    public List<MonthlyAttendanceSummary> refreshSummaries(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return attendanceService.syncSummaries(month);
    }
}
