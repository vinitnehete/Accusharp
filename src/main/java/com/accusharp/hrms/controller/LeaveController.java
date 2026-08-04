package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.LeaveResponse;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.service.leave.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/leaves")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    @PostMapping
    public ResponseEntity<LeaveResponse> apply(@Valid @RequestBody LeaveRequestPayload payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaveService.apply(payload));
    }

    @PostMapping("/{id}/supervisor-approve")
    public LeaveResponse supervisorApprove(@PathVariable Long id,
                                           @Valid @RequestBody LeaveDecisionRequest decision) {
        return leaveService.supervisorApprove(id, decision);
    }

    @PostMapping("/{id}/approve")
    public LeaveResponse approve(@PathVariable Long id, @Valid @RequestBody LeaveDecisionRequest decision) {
        return leaveService.approve(id, decision);
    }

    @PostMapping("/{id}/reject")
    public LeaveResponse reject(@PathVariable Long id, @Valid @RequestBody LeaveDecisionRequest decision) {
        return leaveService.reject(id, decision);
    }

    @PostMapping("/{id}/cancel")
    public LeaveResponse cancel(@PathVariable Long id, @Valid @RequestBody LeaveDecisionRequest decision) {
        return leaveService.cancel(id, decision);
    }

    @GetMapping("/{id}")
    public LeaveResponse getById(@PathVariable Long id) {
        return leaveService.getById(id);
    }

    @GetMapping("/employee/{userId}")
    public List<LeaveResponse> getHistory(@PathVariable String userId) {
        return leaveService.getHistory(userId);
    }

    @GetMapping("/pending/{supervisorUserId}")
    public List<LeaveResponse> getPendingForSupervisor(@PathVariable String supervisorUserId) {
        return leaveService.getPendingFor(supervisorUserId);
    }

    @GetMapping
    public List<LeaveResponse> getByStatus(@RequestParam LeaveStatus status) {
        return leaveService.getByStatus(status);
    }

    @GetMapping("/calendar")
    public List<LeaveResponse> getCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return leaveService.getCalendar(fromDate, toDate);
    }
}
