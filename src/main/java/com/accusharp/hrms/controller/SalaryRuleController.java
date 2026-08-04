package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.SalaryRuleRequest;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.service.SalaryRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @GetMapping
    public SalaryRule get() {
        return salaryRuleService.getActiveRule();
    }

    @PutMapping
    public SalaryRule update(@Valid @RequestBody SalaryRuleRequest request) {
        return salaryRuleService.updateRule(request);
    }
}
