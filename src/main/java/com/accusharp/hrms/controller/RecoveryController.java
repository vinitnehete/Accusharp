package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.BulkImportResult;
import com.accusharp.hrms.dto.RecoveryAmountRequest;
import com.accusharp.hrms.dto.RecoveryDtos;
import com.accusharp.hrms.dto.RecoveryRequest;
import com.accusharp.hrms.dto.RecoveryUpdateRequest;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.recovery.RecoveryService;
import com.accusharp.hrms.util.ParsedCsvRow;
import com.accusharp.hrms.util.RecoveryCsvParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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

/**
 * Loans, salary advances, fines and other recoverable amounts - what was
 * given, what has come back, what is left.
 *
 * <p>Every write records the caller's own authenticated username as the
 * actor; it is never taken from the request body, same convention as
 * {@code PayrollController}.
 */
@RestController
@RequestMapping("/api/recoveries")
@RequiredArgsConstructor
public class RecoveryController {

    private final RecoveryService recoveryService;

    /** Disburses a loan or advance, or levies a fine. */
    @PreAuthorize("@authz.can('RECOVERY_MANAGE')")
    @PostMapping
    public ResponseEntity<RecoveryDtos.RecoveryRow> issue(@AuthenticationPrincipal UserPrincipal principal,
                                                           @Valid @RequestBody RecoveryRequest request) {
        request.setIssuedBy(principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(recoveryService.issue(request));
    }

    /**
     * Raises many at once from a CSV - a month's fine list, or a festival
     * advance run. Every row is attempted independently, so one bad row is
     * reported as a row error instead of aborting the upload, the same
     * contract as every other bulk endpoint.
     */
    @PreAuthorize("@authz.can('RECOVERY_MANAGE')")
    @PostMapping(value = "/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkImportResult<RecoveryDtos.RecoveryRow> bulkIssue(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @RequestParam("file") MultipartFile file) {
        List<ParsedCsvRow<RecoveryRequest>> rows = RecoveryCsvParser.parse(file);
        List<RecoveryDtos.RecoveryRow> succeeded = new ArrayList<>();
        List<BulkImportResult.RowError> errors = new ArrayList<>();

        for (ParsedCsvRow<RecoveryRequest> row : rows) {
            if (!row.isOk()) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), null, row.error()));
                continue;
            }
            RecoveryRequest request = row.value();
            request.setIssuedBy(principal.getUsername());
            try {
                succeeded.add(recoveryService.issue(request));
            } catch (RuntimeException e) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), request.getEmployeeId(), e.getMessage()));
            }
        }
        return BulkImportResult.of(rows.size(), succeeded, errors);
    }

    /** Reschedules the instalment, moves the start period, or puts a recovery on hold. */
    @PreAuthorize("@authz.can('RECOVERY_MANAGE')")
    @PutMapping("/{id}")
    public RecoveryDtos.RecoveryRow update(@PathVariable Long id,
                                           @Valid @RequestBody RecoveryUpdateRequest request) {
        return recoveryService.update(id, request);
    }

    /** Writes off part or all of what is owed. */
    @PreAuthorize("@authz.can('RECOVERY_MANAGE')")
    @PostMapping("/{id}/waive")
    public RecoveryDtos.RecoveryRow waive(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable Long id,
                                          @Valid @RequestBody RecoveryAmountRequest request) {
        return recoveryService.waive(id, request, principal.getUsername());
    }

    /** Records an amount settled outside payroll - cash, a bank transfer, or a wrongly-entered principal. */
    @PreAuthorize("@authz.can('RECOVERY_MANAGE')")
    @PostMapping("/{id}/adjust")
    public RecoveryDtos.RecoveryRow adjust(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody RecoveryAmountRequest request) {
        return recoveryService.adjust(id, request, principal.getUsername());
    }

    /** Withdraws a recovery raised in error. Refused once anything has been recovered against it - waive the balance instead. */
    @PreAuthorize("@authz.can('RECOVERY_MANAGE')")
    @PostMapping("/{id}/cancel")
    public RecoveryDtos.RecoveryRow cancel(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id,
                                           @RequestParam(required = false) @Size(max = 300) String remarks) {
        return recoveryService.cancel(id, remarks, principal.getUsername());
    }

    /** Every open recovery in the caller's company - the register HR works from before a payroll run. */
    @PreAuthorize("@authz.can('RECOVERY_READ')")
    @GetMapping
    public List<RecoveryDtos.RecoveryRow> getOutstanding(
            @RequestParam(defaultValue = "false") boolean includeSettled) {
        return recoveryService.getOutstanding(includeSettled);
    }

    @PreAuthorize("@authz.can('RECOVERY_READ')")
    @GetMapping("/employee/{employeeId}")
    public List<RecoveryDtos.RecoveryRow> getForEmployee(@PathVariable String employeeId) {
        return recoveryService.getForEmployee(employeeId);
    }

    /** Balances by type plus the full ledger - every issue, repayment, reversal, waiver and adjustment. */
    @PreAuthorize("@authz.can('RECOVERY_READ')")
    @GetMapping("/employee/{employeeId}/statement")
    public RecoveryDtos.Statement getStatement(@PathVariable String employeeId) {
        return recoveryService.getStatement(employeeId);
    }

    /**
     * What payroll would recover for this employee in this period - what the
     * generate screen pre-fills, so HR runs payroll seeing the amounts about
     * to apply rather than typing them from memory.
     */
    @PreAuthorize("@authz.can('RECOVERY_READ')")
    @GetMapping("/employee/{employeeId}/schedule")
    public RecoveryDtos.Schedule getSchedule(@PathVariable String employeeId,
                                             @RequestParam @Min(1) @Max(12) int month,
                                             @RequestParam @Min(2000) @Max(2100) int year) {
        return recoveryService.getSchedule(employeeId, month, year);
    }
}
