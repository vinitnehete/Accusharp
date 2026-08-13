package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.SalaryRuleRequest;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.service.SalaryRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/salary-rules")
@RequiredArgsConstructor
public class SalaryRuleController {

    private final SalaryRuleService salaryRuleService;

    @PreAuthorize("@authz.can('SALARY_RULE_READ')")
    @GetMapping
    public SalaryRule get() {
        return salaryRuleService.getActiveRule();
    }

    @PreAuthorize("@authz.can('SALARY_RULE_MANAGE')")
    @PutMapping
    public SalaryRule update(@Valid @RequestBody SalaryRuleRequest request) {
        return salaryRuleService.updateRule(request);
    }
}
