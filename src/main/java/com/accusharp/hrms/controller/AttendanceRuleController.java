package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.AttendanceRuleRequest;
import com.accusharp.hrms.entity.AttendanceRule;
import com.accusharp.hrms.service.AttendanceRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attendance-rules")
@RequiredArgsConstructor
public class AttendanceRuleController {

    private final AttendanceRuleService attendanceRuleService;

    @PreAuthorize("@authz.can('ATTENDANCE_RULE_READ')")
    @GetMapping
    public AttendanceRule get() {
        return attendanceRuleService.getActiveRule();
    }

    @PreAuthorize("@authz.can('ATTENDANCE_RULE_MANAGE')")
    @PutMapping
    public AttendanceRule update(@Valid @RequestBody AttendanceRuleRequest request) {
        return attendanceRuleService.updateRule(request);
    }
}
