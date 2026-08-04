package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.service.payroll.PayrollService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollService payrollService;

    /** Generates the period once; a repeat call is a 409. */
    @PostMapping("/generate")
    public ResponseEntity<Payroll> generate(@Valid @RequestBody PayrollRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payrollService.generate(request));
    }

    /** Recomputes the period as a new revision; the previous one is retained. */
    @PostMapping("/regenerate")
    public Payroll regenerate(@Valid @RequestBody PayrollRequest request) {
        return payrollService.regenerate(request);
    }

    @PostMapping("/generate-all")
    public ResponseEntity<List<Payroll>> generateForAll(@RequestParam int month,
                                                        @RequestParam int year,
                                                        @RequestParam(required = false) String generatedBy) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payrollService.generateForAll(month, year, generatedBy));
    }

    @GetMapping("/{id}")
    public Payroll getById(@PathVariable Long id) {
        return payrollService.getById(id);
    }

    @GetMapping("/employee/{employeeId}")
    public List<Payroll> getHistory(@PathVariable String employeeId) {
        return payrollService.getHistory(employeeId);
    }

    @GetMapping("/employee/{employeeId}/period")
    public Payroll getCurrent(@PathVariable String employeeId,
                              @RequestParam int month,
                              @RequestParam int year) {
        return payrollService.getCurrent(employeeId, month, year);
    }

    /** Every revision of a period, newest first - the audit trail. */
    @GetMapping("/employee/{employeeId}/revisions")
    public List<Payroll> getRevisions(@PathVariable String employeeId,
                                      @RequestParam int month,
                                      @RequestParam int year) {
        return payrollService.getRevisions(employeeId, month, year);
    }

    @GetMapping
    public List<Payroll> getPeriod(@RequestParam int month, @RequestParam int year) {
        return payrollService.getPeriod(month, year);
    }
}
