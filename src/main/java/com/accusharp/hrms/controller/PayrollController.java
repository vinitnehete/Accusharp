package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.BulkImportResult;
import com.accusharp.hrms.dto.PayrollDebugRow;
import com.accusharp.hrms.dto.PayrollRequest;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.payroll.PayrollService;
import com.accusharp.hrms.util.ParsedCsvRow;
import com.accusharp.hrms.util.PayrollCsvParser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
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

    /**
     * Runs payroll for every active employee who doesn't already have this
     * period generated. Each employee is generated as its own call to {@link
     * PayrollService#generate} - and therefore its own transaction - so one
     * employee's failure (a roster gap, missing attendance) is reported as a
     * row error instead of rolling back everyone else already generated in
     * this run. Same shape as {@link #bulkGenerate}'s per-row isolation.
     */
    @PreAuthorize("@authz.can('PAYROLL_PROCESS')")
    @PostMapping("/generate-all")
    public BulkImportResult<Payroll> generateForAll(@AuthenticationPrincipal UserPrincipal principal,
                                                     @RequestParam int month,
                                                     @RequestParam int year) {
        List<String> employeeIds = payrollService.pendingGenerationEmployeeIds(month, year);
        List<Payroll> succeeded = new ArrayList<>();
        List<BulkImportResult.RowError> errors = new ArrayList<>();

        int row = 0;
        for (String employeeId : employeeIds) {
            row++;
            PayrollRequest request = new PayrollRequest();
            request.setEmployeeId(employeeId);
            request.setMonth(month);
            request.setYear(year);
            request.setGeneratedBy(principal.getUsername());
            try {
                succeeded.add(payrollService.generate(request));
            } catch (RuntimeException e) {
                errors.add(new BulkImportResult.RowError(row, employeeId, e.getMessage()));
            }
        }
        return BulkImportResult.of(employeeIds.size(), succeeded, errors);
    }

    /**
     * Runs payroll for a whole company from one CSV upload - one row per
     * employee, carrying whatever one-off bonus/incentive/deduction amounts
     * that month needs (see {@link PayrollCsvParser} for the header).
     * {@code month}/{@code year} apply to the entire file, same as {@link
     * #generateForAll}. Every row runs independently: a row for a period
     * already generated is reported as a row error rather than aborting the
     * upload, unless {@code regenerate=true}, in which case it's recomputed
     * as a new revision instead - same choice {@link #regenerate} exposes for
     * a single employee.
     */
    @PreAuthorize("@authz.can('PAYROLL_PROCESS')")
    @PostMapping(value = "/bulk-generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkImportResult<Payroll> bulkGenerate(@AuthenticationPrincipal UserPrincipal principal,
                                                    @RequestParam("file") MultipartFile file,
                                                    @RequestParam int month,
                                                    @RequestParam int year,
                                                    @RequestParam(defaultValue = "false") boolean regenerate) {
        List<ParsedCsvRow<PayrollRequest>> rows = PayrollCsvParser.parse(file, month, year);
        List<Payroll> succeeded = new ArrayList<>();
        List<BulkImportResult.RowError> errors = new ArrayList<>();

        for (ParsedCsvRow<PayrollRequest> row : rows) {
            if (!row.isOk()) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), null, row.error()));
                continue;
            }
            PayrollRequest request = row.value();
            request.setGeneratedBy(principal.getUsername());
            try {
                succeeded.add(payrollService.generate(request));
            } catch (ConflictException alreadyGenerated) {
                if (!regenerate) {
                    errors.add(new BulkImportResult.RowError(row.rowNumber(), request.getEmployeeId(),
                            alreadyGenerated.getMessage()));
                    continue;
                }
                try {
                    succeeded.add(payrollService.regenerate(request));
                } catch (RuntimeException e) {
                    errors.add(new BulkImportResult.RowError(row.rowNumber(), request.getEmployeeId(), e.getMessage()));
                }
            } catch (RuntimeException e) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), request.getEmployeeId(), e.getMessage()));
            }
        }
        return BulkImportResult.of(rows.size(), succeeded, errors);
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

    /**
     * Every field the calculation consumed and produced for every employee in
     * this period, plus a live comparison against the employee's current
     * master salary data and the company's current {@code SalaryRule} - built
     * for tracing a wrong number back to its cause instead of recomputing by
     * hand. See {@link PayrollDebugRow}.
     */
    @PreAuthorize("@authz.can('PAYROLL_READ')")
    @GetMapping("/debug")
    public List<PayrollDebugRow> getPeriodDebug(@RequestParam int month, @RequestParam int year) {
        return payrollService.getPeriodDebugForCaller(month, year);
    }
}
