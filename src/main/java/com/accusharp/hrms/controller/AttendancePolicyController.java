package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.policy.AttendancePolicyDtos;
import com.accusharp.hrms.dto.policy.AttendancePolicyPreviewDtos;
import com.accusharp.hrms.dto.policy.AttendancePolicyRuleRequest;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.service.policy.AttendancePolicyPreviewService;
import com.accusharp.hrms.service.policy.AttendancePolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Per-population attendance policy: the rules that decide when a late arrival
 * costs half a day, what an early-exit budget is worth, and who earns overtime.
 *
 * <p><b>There is no PUT.</b> A rule is never edited - changing one appends a
 * version with a later {@code effectiveFrom}, and ending one appends a version
 * with {@code enabled = false}. The chain is the history, and an already-priced
 * day can always be traced to the exact version that priced it. DELETE only
 * accepts a version that has not taken effect yet.
 *
 * <p>Everything is scoped through {@code TenantContext}: a company sees its own
 * rules plus the shared {@code company = null} catalog, can write only its own,
 * and can never edit a shared row - the same shape {@code Shift} and {@code
 * Category} have used since SECURITY.md Phase 6.
 */
@RestController
@RequestMapping("/api/attendance-policy")
@RequiredArgsConstructor
public class AttendancePolicyController {

    private final AttendancePolicyService attendancePolicyService;
    private final AttendancePolicyPreviewService previewService;

    /** Every rule this company can see, newest version of each chain first. */
    @PreAuthorize("@authz.can('ATTENDANCE_POLICY_READ')")
    @GetMapping("/rules")
    public List<AttendancePolicyDtos.RuleResponse> list(
            @RequestParam(required = false) RuleType ruleType,
            @RequestParam(required = false) RuleScope scope) {
        return attendancePolicyService.list(ruleType, scope);
    }

    /** Appends the next version of a rule. Never edits an existing one. */
    @PreAuthorize("@authz.can('ATTENDANCE_POLICY_MANAGE')")
    @PostMapping("/rules")
    public AttendancePolicyDtos.RuleResponse create(@Valid @RequestBody AttendancePolicyRuleRequest request) {
        return attendancePolicyService.create(request);
    }

    /**
     * Removes a version that has not taken effect yet. Anything that has ever
     * been in force is superseded with a disabled version instead - a day it
     * priced may already be on a payslip.
     */
    @PreAuthorize("@authz.can('ATTENDANCE_POLICY_MANAGE')")
    @DeleteMapping("/rules/{id}")
    public void delete(@PathVariable Long id) {
        attendancePolicyService.delete(id);
    }

    /**
     * What actually applies to one employee on one date - the rule that won for
     * each type, why, and the rules it beat.
     *
     * <p>This is the endpoint to reach for when somebody asks why a day came out
     * the way it did. It also returns the company-wide {@code AttendanceRule}
     * thresholds the policy sits on top of, so the answer is in one place even
     * though two tables produce it.
     */
    @PreAuthorize("@authz.can('ATTENDANCE_POLICY_READ')")
    @GetMapping("/effective")
    public AttendancePolicyDtos.EffectivePolicyResponse effective(
            @RequestParam String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendancePolicyService.effectivePolicy(userId, date);
    }

    /**
     * Re-evaluates a past month under a proposed rule set and returns the diff -
     * days changed, LOP delta and overtime delta per employee - <b>without
     * persisting anything</b>.
     *
     * <p>Run this before saving a rule. {@code Attendance.md}'s worked example
     * ends on the lesson that a misconfiguration is invisible until somebody
     * reads the numbers; this is how to read them first.
     */
    @PreAuthorize("@authz.can('ATTENDANCE_POLICY_MANAGE')")
    @PostMapping("/preview")
    public AttendancePolicyPreviewDtos.PreviewResponse preview(
            @Valid @RequestBody AttendancePolicyPreviewDtos.PreviewRequest request) {
        return previewService.preview(request);
    }
}
