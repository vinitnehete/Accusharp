package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.BulkShiftAssignmentRequest;
import com.accusharp.hrms.dto.CopyScheduleRequest;
import com.accusharp.hrms.dto.MonthlyPlannerResponse;
import com.accusharp.hrms.dto.ShiftAssignmentRequest;
import com.accusharp.hrms.dto.ShiftRotationRequest;
import com.accusharp.hrms.dto.ShiftScheduleResponse;
import com.accusharp.hrms.dto.ShiftSwapRequest;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.shift.ShiftSchedulingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Shift scheduling: assignment, planner, rotation, copy, override and swap.
 *
 * <p>{@code assignedBy} on every mutating request is overwritten with the
 * caller's own authenticated username below - {@code ShiftSchedulingService}
 * uses it to enforce "a supervisor may only schedule their own team", so it
 * must be who actually called the API, never a client-supplied value.
 */
@RestController
@RequestMapping("/api/shift-schedules")
@RequiredArgsConstructor
public class ShiftScheduleController {

    private final ShiftSchedulingService shiftSchedulingService;

    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_MANAGE')")
    @PostMapping
    public ResponseEntity<ShiftScheduleResponse> assign(@AuthenticationPrincipal UserPrincipal principal,
                                                         @Valid @RequestBody ShiftAssignmentRequest request) {
        request.setAssignedBy(principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(shiftSchedulingService.assign(request));
    }

    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_MANAGE')")
    @PostMapping("/bulk")
    public List<ShiftScheduleResponse> assignBulk(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody BulkShiftAssignmentRequest request) {
        request.setAssignedBy(principal.getUsername());
        return shiftSchedulingService.assignBulk(request);
    }

    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_MANAGE')")
    @PostMapping("/auto-rotate")
    public List<ShiftScheduleResponse> autoRotate(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody ShiftRotationRequest request) {
        request.setAssignedBy(principal.getUsername());
        return shiftSchedulingService.autoRotate(request);
    }

    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_MANAGE')")
    @PostMapping("/copy-month")
    public List<ShiftScheduleResponse> copyMonth(@AuthenticationPrincipal UserPrincipal principal,
                                                  @Valid @RequestBody CopyScheduleRequest request) {
        request.setAssignedBy(principal.getUsername());
        return shiftSchedulingService.copyMonth(request);
    }

    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_MANAGE')")
    @PostMapping("/swap")
    public List<ShiftScheduleResponse> swap(@AuthenticationPrincipal UserPrincipal principal,
                                            @Valid @RequestBody ShiftSwapRequest request) {
        request.setAssignedBy(principal.getUsername());
        return shiftSchedulingService.swap(request);
    }

    /** Marks every mandatory holiday in the month as a week off on the roster. */
    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_MANAGE')")
    @PostMapping("/holiday-override")
    public Map<String, Integer> holidayOverride(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return Map.of("updatedDays", shiftSchedulingService.applyHolidayOverride(month));
    }

    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_READ')")
    @GetMapping("/{userId}")
    public List<ShiftScheduleResponse> getRoster(
            @PathVariable String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return shiftSchedulingService.getRoster(userId, fromDate, toDate);
    }

    /** Calendar-shaped roster for the monthly planner UI. */
    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_READ')")
    @GetMapping("/planner")
    public MonthlyPlannerResponse getPlanner(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) String supervisorUserId) {
        return shiftSchedulingService.getMonthlyPlanner(month, supervisorUserId);
    }

    @PreAuthorize("@authz.can('SHIFT_SCHEDULE_MANAGE')")
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteRange(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        shiftSchedulingService.deleteRange(userId, fromDate, toDate, principal.getUsername());
        return ResponseEntity.noContent().build();
    }
}
