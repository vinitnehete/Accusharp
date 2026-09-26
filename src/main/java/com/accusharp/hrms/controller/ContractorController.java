package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.dto.ContractorEmployeeRequest;
import com.accusharp.hrms.dto.ContractorEmployeeResponse;
import com.accusharp.hrms.dto.ContractorReportDtos;
import com.accusharp.hrms.dto.ContractorRequest;
import com.accusharp.hrms.dto.ContractorResponse;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.contractor.ContractorAttendanceService;
import com.accusharp.hrms.service.contractor.ContractorEmployeeService;
import com.accusharp.hrms.service.contractor.ContractorService;
import com.accusharp.hrms.service.report.ContractorAttendanceReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Labour contractors, the workforce they deploy, that workforce's attendance,
 * and the reports sent back to them.
 *
 * <p>One controller rather than four because they are one screen's worth of
 * work and share one authorization story: {@code CONTRACTOR_READ} to look,
 * {@code CONTRACTOR_MANAGE} to change - except attendance generation, which
 * additionally requires {@code ATTENDANCE_GENERATE}, because writing
 * attendance rows is the same act whoever the subject is and must not become
 * reachable through a narrower grant.
 *
 * <p>{@code generatedBy} is taken from the bearer token, never the request
 * body - the same rule {@code AttendanceController} states, and the one
 * {@code AttendanceService} relies on for its HR/ADMIN check.
 */
@RestController
@RequestMapping("/api/contractors")
@RequiredArgsConstructor
public class ContractorController {

    private final ContractorService contractorService;
    private final ContractorEmployeeService contractorEmployeeService;
    private final ContractorAttendanceService contractorAttendanceService;
    private final ContractorAttendanceReportService contractorReportService;

    // ---- contractors --------------------------------------------------------

    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @PostMapping
    public ResponseEntity<ContractorResponse> create(@Valid @RequestBody ContractorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contractorService.create(request));
    }

    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @PutMapping("/{id}")
    public ContractorResponse update(@PathVariable Long id, @Valid @RequestBody ContractorRequest request) {
        return contractorService.update(id, request);
    }

    /** Every contractor of the company; {@code activeOnly} for the pickers. */
    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping
    public List<ContractorResponse> getAll(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return activeOnly ? contractorService.getActive() : contractorService.getAll();
    }

    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping("/{id}")
    public ContractorResponse getById(@PathVariable Long id) {
        return contractorService.getById(id);
    }

    /** Deactivates, never deletes - see {@code ContractorService#deactivate}. */
    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @DeleteMapping("/{id}")
    public ContractorResponse deactivate(@PathVariable Long id) {
        return contractorService.deactivate(id);
    }

    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @PostMapping("/{id}/reactivate")
    public ContractorResponse reactivate(@PathVariable Long id) {
        return contractorService.reactivate(id);
    }

    // ---- the workforce ------------------------------------------------------

    /**
     * Every contractor's workers, or one contractor's when
     * {@code contractorId} is given. Each row carries its contractor's name -
     * a company with several contractors reads this list as one table
     * grouped by who supplied whom.
     */
    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping("/employees")
    public List<ContractorEmployeeResponse> workforce(@RequestParam(required = false) Long contractorId,
                                                       @RequestParam(defaultValue = "false") boolean activeOnly) {
        return contractorEmployeeService.getAll(contractorId, activeOnly);
    }

    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping("/{id}/employees")
    public List<ContractorEmployeeResponse> employeesOf(@PathVariable Long id) {
        return contractorEmployeeService.getByContractor(id);
    }

    /**
     * Onboards one of the contractor's workers. The contractor is the path
     * variable, not a body field, so a worker can never be filed under a
     * contractor the URL did not name.
     */
    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @PostMapping("/{id}/employees")
    public ResponseEntity<ContractorEmployeeResponse> addEmployee(
            @PathVariable Long id, @Valid @RequestBody ContractorEmployeeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contractorEmployeeService.create(id, request));
    }

    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping("/employees/{employeeId}")
    public ContractorEmployeeResponse getEmployee(@PathVariable Long employeeId) {
        return contractorEmployeeService.getById(employeeId);
    }

    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @PutMapping("/employees/{employeeId}")
    public ContractorEmployeeResponse updateEmployee(@PathVariable Long employeeId,
                                                      @Valid @RequestBody ContractorEmployeeRequest request) {
        return contractorEmployeeService.update(employeeId, request);
    }

    /** Same agency worker, different agency - see {@code reassignContractor}'s Javadoc. */
    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @PatchMapping("/employees/{employeeId}/contractor")
    public ContractorEmployeeResponse reassignEmployee(@PathVariable Long employeeId,
                                                        @RequestParam Long contractorId) {
        return contractorEmployeeService.reassignContractor(employeeId, contractorId);
    }

    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @DeleteMapping("/employees/{employeeId}")
    public ContractorEmployeeResponse deactivateEmployee(@PathVariable Long employeeId) {
        return contractorEmployeeService.deactivate(employeeId);
    }

    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE')")
    @PostMapping("/employees/{employeeId}/reactivate")
    public ContractorEmployeeResponse reactivateEmployee(@PathVariable Long employeeId) {
        return contractorEmployeeService.reactivate(employeeId);
    }

    // ---- attendance ---------------------------------------------------------

    /**
     * Generates the stored attendance for one contractor's workforce - the
     * identical engine the company console runs, over a different population.
     *
     * <p>Requires {@code ATTENDANCE_GENERATE} on top of
     * {@code CONTRACTOR_MANAGE}: this writes attendance rows, and that is the
     * same act regardless of who the subject is.
     *
     * <p>{@code includeUnrostered} defaults to false here, unlike the company
     * console - see {@code ContractorAttendanceService#generate}.
     */
    @PreAuthorize("@authz.can('CONTRACTOR_MANAGE') and @authz.can('ATTENDANCE_GENERATE')")
    @PostMapping("/{id}/attendance/generate")
    public AttendanceGenerationResponse generateAttendance(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(defaultValue = "false") boolean overwriteManual,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean includeUnrostered) {
        return contractorAttendanceService.generate(id, month, principal.getUsername(),
                overwriteManual, dryRun, includeUnrostered);
    }

    // ---- reports ------------------------------------------------------------

    /** The month's cover line plus every worker on it - what the contractor is sent. */
    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping("/{id}/reports/attendance/monthly")
    public ContractorReportDtos.ContractorMonthlyReport monthlyReport(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return contractorReportService.monthlyReport(id, month);
    }

    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping(value = "/{id}/reports/attendance/monthly/export", produces = "text/csv")
    public ResponseEntity<String> monthlyReportExport(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return csv(contractorReportService.monthlyCsv(id, month),
                "contractor-attendance-%d-%s.csv".formatted(id, month));
    }

    /** The day-by-day register behind the month, for a contractor to reconcile against. */
    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping("/{id}/reports/attendance/daily")
    public List<ContractorReportDtos.ContractorDailyRow> dailyRegister(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return contractorReportService.dailyRegister(id, from, to);
    }

    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping(value = "/{id}/reports/attendance/daily/export", produces = "text/csv")
    public ResponseEntity<String> dailyRegisterExport(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return csv(contractorReportService.dailyCsv(id, from, to),
                "contractor-daily-%d-%s-to-%s.csv".formatted(id, from, to));
    }

    /** Every contractor's month, one line each - the only side-by-side view. */
    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping("/reports/attendance/summary")
    public List<ContractorReportDtos.ContractorSummaryRow> allContractorsSummary(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return contractorReportService.allContractorsSummary(month);
    }

    @PreAuthorize("@authz.can('CONTRACTOR_READ')")
    @GetMapping(value = "/reports/attendance/summary/export", produces = "text/csv")
    public ResponseEntity<String> allContractorsSummaryExport(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return csv(contractorReportService.allContractorsCsv(month),
                "contractor-summary-%s.csv".formatted(month));
    }

    /** Same download shape every other export in this app uses. */
    private ResponseEntity<String> csv(String body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
