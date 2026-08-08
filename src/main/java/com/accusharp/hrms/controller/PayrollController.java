package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.payroll.PayrollService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** {@code generatedBy} is always the caller's own authenticated username, never client-supplied. */
@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollService payrollService;

    /** Generates the period once; a repeat call is a 409. */
    @PreAuthorize("@authz.can('PAYROLL_PROCESS')")
    @PostMapping("/generate")
    public ResponseEntity<Payroll> generate(@AuthenticationPrincipal UserPrincipal principal,
                                            @Valid @RequestBody PayrollRequest request) {
        request.setGeneratedBy(principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(payrollService.generate(request));
    }

    /** Recomputes the period as a new revision; the previous one is retained. */
    @PreAuthorize("@authz.can('PAYROLL_PROCESS')")
    @PostMapping("/regenerate")
    public Payroll regenerate(@AuthenticationPrincipal UserPrincipal principal,
                              @Valid @RequestBody PayrollRequest request) {
        request.setGeneratedBy(principal.getUsername());
        return payrollService.regenerate(request);
    }

    @PreAuthorize("@authz.can('PAYROLL_PROCESS')")
    @PostMapping("/generate-all")
    public ResponseEntity<List<Payroll>> generateForAll(@AuthenticationPrincipal UserPrincipal principal,
                                                        @RequestParam int month,
                                                        @RequestParam int year) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payrollService.generateForAll(month, year, principal.getUsername()));
    }

    @PreAuthorize("@authz.can('PAYROLL_READ')")
    @GetMapping("/{id}")
    public Payroll getById(@PathVariable Long id) {
        return payrollService.getById(id);
    }

    @PreAuthorize("@authz.can('PAYROLL_READ')")
    @GetMapping("/employee/{employeeId}")
    public List<Payroll> getHistory(@PathVariable String employeeId) {
        return payrollService.getHistory(employeeId);
    }

    @PreAuthorize("@authz.can('PAYROLL_READ')")
    @GetMapping("/employee/{employeeId}/period")
    public Payroll getCurrent(@PathVariable String employeeId,
                              @RequestParam int month,
                              @RequestParam int year) {
        return payrollService.getCurrent(employeeId, month, year);
    }

    /** Every revision of a period, newest first - the audit trail. */
    @PreAuthorize("@authz.can('PAYROLL_READ')")
    @GetMapping("/employee/{employeeId}/revisions")
    public List<Payroll> getRevisions(@PathVariable String employeeId,
                                      @RequestParam int month,
                                      @RequestParam int year) {
        return payrollService.getRevisions(employeeId, month, year);
    }

    @PreAuthorize("@authz.can('PAYROLL_READ')")
    @GetMapping
    public List<Payroll> getPeriod(@RequestParam int month, @RequestParam int year) {
        return payrollService.getPeriodForCaller(month, year);
    }
}
