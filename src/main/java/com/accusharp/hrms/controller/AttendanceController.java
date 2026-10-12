package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.AttendanceBulkUploadResponse;
import com.accusharp.hrms.dto.AttendanceBulkUploadRow;
import com.accusharp.hrms.dto.AttendanceCorrectionRequest;
import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.dto.AttendanceRecordResponse;
import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.dto.MonthlyAttendanceResponse;
import com.accusharp.hrms.dto.BulkImportResult;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.util.AttendanceCsvParser;
import com.accusharp.hrms.util.ParsedCsvRow;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
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

    /**
     * Declares a stretch of days for one employee with no punch times - for
     * staff who are not on the biometric device at all, or any period a device
     * outage left without punches. Days it declines to write (locked, already
     * on approved leave, a week off or holiday, or unrostered) come back in
     * {@code skipped} rather than being silently dropped.
     */
    @PreAuthorize("@authz.can('ATTENDANCE_CORRECT')")
    @PostMapping("/bulk-upload")
    public AttendanceBulkUploadResponse bulkUpload(@AuthenticationPrincipal UserPrincipal principal,
                                                    @Valid @RequestBody AttendanceBulkUploadRow row) {
        row.setUploadedBy(principal.getUsername());
        return attendanceService.uploadDays(row);
    }

    /**
     * The same thing for a whole company from one CSV - one row per
     * employee-period, each row applied independently so a bad row is reported
     * rather than aborting the file, the same contract as every other bulk
     * import. See {@link AttendanceCsvParser} for the header.
     */
    @PreAuthorize("@authz.can('ATTENDANCE_CORRECT')")
    @PostMapping(value = "/bulk-upload/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttendanceBulkUploadResponse bulkUploadCsv(@AuthenticationPrincipal UserPrincipal principal,
                                                       @RequestParam("file") MultipartFile file) {
        List<ParsedCsvRow<AttendanceBulkUploadRow>> rows = AttendanceCsvParser.parse(file);
        List<AttendanceRecordResponse> applied = new ArrayList<>();
        List<AttendanceBulkUploadResponse.SkippedDay> skipped = new ArrayList<>();
        List<BulkImportResult.RowError> errors = new ArrayList<>();

        for (ParsedCsvRow<AttendanceBulkUploadRow> row : rows) {
            if (!row.isOk()) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), null, row.error()));
                continue;
            }
            AttendanceBulkUploadRow value = row.value();
            value.setUploadedBy(principal.getUsername());
            try {
                AttendanceBulkUploadResponse result = attendanceService.uploadDays(value);
                applied.addAll(result.applied());
                skipped.addAll(result.skipped());
            } catch (RuntimeException e) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), value.getUserId(), e.getMessage()));
            }
        }
        return new AttendanceBulkUploadResponse(rows.size(), applied.size(), skipped.size(),
                applied, skipped, errors);
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
