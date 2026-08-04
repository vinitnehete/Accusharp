package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.BulkShiftAssignmentRequest;
import com.accusharp.hrms.dto.CopyScheduleRequest;
import com.accusharp.hrms.dto.MonthlyPlannerResponse;
import com.accusharp.hrms.dto.ShiftAssignmentRequest;
import com.accusharp.hrms.dto.ShiftRotationRequest;
import com.accusharp.hrms.dto.ShiftScheduleResponse;
import com.accusharp.hrms.dto.ShiftSwapRequest;
import com.accusharp.hrms.service.shift.ShiftSchedulingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** Shift scheduling: assignment, planner, rotation, copy, override and swap. */
@RestController
@RequestMapping("/api/shift-schedules")
@RequiredArgsConstructor
public class ShiftScheduleController {

    private final ShiftSchedulingService shiftSchedulingService;

    @PostMapping
    public ResponseEntity<ShiftScheduleResponse> assign(@Valid @RequestBody ShiftAssignmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shiftSchedulingService.assign(request));
    }

    @PostMapping("/bulk")
    public List<ShiftScheduleResponse> assignBulk(@Valid @RequestBody BulkShiftAssignmentRequest request) {
        return shiftSchedulingService.assignBulk(request);
    }

    @PostMapping("/auto-rotate")
    public List<ShiftScheduleResponse> autoRotate(@Valid @RequestBody ShiftRotationRequest request) {
        return shiftSchedulingService.autoRotate(request);
    }

    @PostMapping("/copy-month")
    public List<ShiftScheduleResponse> copyMonth(@Valid @RequestBody CopyScheduleRequest request) {
        return shiftSchedulingService.copyMonth(request);
    }

    @PostMapping("/swap")
    public List<ShiftScheduleResponse> swap(@Valid @RequestBody ShiftSwapRequest request) {
        return shiftSchedulingService.swap(request);
    }

    /** Marks every mandatory holiday in the month as a week off on the roster. */
    @PostMapping("/holiday-override")
    public Map<String, Integer> holidayOverride(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return Map.of("updatedDays", shiftSchedulingService.applyHolidayOverride(month));
    }

    @GetMapping("/{userId}")
    public List<ShiftScheduleResponse> getRoster(
            @PathVariable String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return shiftSchedulingService.getRoster(userId, fromDate, toDate);
    }

    /** Calendar-shaped roster for the monthly planner UI. */
    @GetMapping("/planner")
    public MonthlyPlannerResponse getPlanner(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) String supervisorUserId) {
        return shiftSchedulingService.getMonthlyPlanner(month, supervisorUserId);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteRange(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        shiftSchedulingService.deleteRange(userId, fromDate, toDate);
        return ResponseEntity.noContent().build();
    }
}
