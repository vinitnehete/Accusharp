package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.WorkPolicyRequest;
import com.accusharp.hrms.dto.WorkPolicyResponse;
import com.accusharp.hrms.service.policy.WorkPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Who follows the attendance process, and who is simply paid - see
 * {@link com.accusharp.hrms.entity.WorkPolicy}.
 *
 * <p>Its own permission rather than {@code ATTENDANCE_POLICY_MANAGE}: an
 * attendance policy shapes what a tracked day is worth, while this decides
 * whether somebody is tracked or paid a fixed salary at all, which is a payroll
 * decision and a strictly larger blast radius.
 */
@RestController
@RequestMapping("/api/work-policies")
@RequiredArgsConstructor
public class WorkPolicyController {

    private final WorkPolicyService workPolicyService;

    @PreAuthorize("@authz.can('WORK_POLICY_READ')")
    @GetMapping
    public List<WorkPolicyResponse> list() {
        return workPolicyService.list();
    }

    /** Appends the next version for this population; ending one is a version with {@code enabled: false}. */
    @PreAuthorize("@authz.can('WORK_POLICY_MANAGE')")
    @PostMapping
    public ResponseEntity<WorkPolicyResponse> create(@Valid @RequestBody WorkPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workPolicyService.create(request));
    }

    /** What one employee actually follows on a date, and under which rule. */
    @PreAuthorize("@authz.can('WORK_POLICY_READ')")
    @GetMapping("/effective")
    public WorkPolicyResponse effective(@RequestParam String userId,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return workPolicyService.effectiveFor(userId, date == null ? LocalDate.now() : date);
    }
}
