package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.BulkImportResult;
import com.accusharp.hrms.dto.EmployeeCreationResponse;
import com.accusharp.hrms.dto.EmployeeRequest;
import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.dto.SalaryRevisionRequest;
import com.accusharp.hrms.dto.SalaryStructureRequest;
import com.accusharp.hrms.entity.SalaryRevision;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.AuthenticationFailedException;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.util.EmployeeCredentialsCsvWriter;
import com.accusharp.hrms.util.EmployeeCsvParser;
import com.accusharp.hrms.util.ParsedCsvRow;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final Validator validator;

    @PreAuthorize("@authz.can('EMPLOYEE_CREATE')")
    @PostMapping
    public ResponseEntity<EmployeeCreationResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                            @Valid @RequestBody EmployeeRequest request) {
        assertNotGrantingAdminUnlessAdmin(principal, request);
        applyTenantScope(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.create(request));
    }

    /**
     * Bulk onboarding from a CSV upload (see {@link EmployeeCsvParser} for the
     * expected header). Every row is attempted independently through the same
     * {@link #create} path - same admin-escalation guard, same tenant scoping,
     * same one-time temporary password per employee - so one bad row (a
     * duplicate userId, a missing required column) fails only that row; the
     * rest of the file still gets created and returned in {@code succeeded}.
     *
     * <p>{@code format=json} (default) returns the full {@link BulkImportResult}
     * - every created employee plus every row error, for a caller that wants
     * to show both. {@code format=csv} instead returns a downloadable sheet of
     * just {@code userId, employeeCode, employeeName, temporaryPassword} for
     * the rows that succeeded - there is no email/SMS infrastructure in this
     * app to deliver 50-500 temporary passwords automatically (see
     * SECURITY.md), so this is what HR actually hands out. Built from the same
     * in-memory result this call already produced, never a second lookup.
     */
    @PreAuthorize("@authz.can('EMPLOYEE_CREATE')")
    @PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> bulkImport(@AuthenticationPrincipal UserPrincipal principal,
                                         @RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = "json") String format) {
        List<ParsedCsvRow<EmployeeRequest>> rows = EmployeeCsvParser.parse(file);
        List<EmployeeCreationResponse> succeeded = new ArrayList<>();
        List<BulkImportResult.RowError> errors = new ArrayList<>();

        for (ParsedCsvRow<EmployeeRequest> row : rows) {
            if (!row.isOk()) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), null, row.error()));
                continue;
            }
            EmployeeRequest request = row.value();
            String violations = validate(request);
            if (violations != null) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), request.getUserId(), violations));
                continue;
            }
            try {
                assertNotGrantingAdminUnlessAdmin(principal, request);
                applyTenantScope(principal, request);
                succeeded.add(employeeService.create(request));
            } catch (RuntimeException e) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), request.getUserId(), e.getMessage()));
            }
        }

        if ("csv".equalsIgnoreCase(format)) {
            byte[] csv = EmployeeCredentialsCsvWriter.write(succeeded);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"employee-credentials.csv\"")
                    .body(csv);
        }
        return ResponseEntity.ok(BulkImportResult.of(rows.size(), succeeded, errors));
    }

    /** CSV rows are built by hand, not bound from a validated {@code @RequestBody}, so validation runs explicitly. */
    private String validate(EmployeeRequest request) {
        Set<ConstraintViolation<EmployeeRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return null;
        }
        return violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
    }

    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PutMapping("/{id}")
    public EmployeeResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                   @PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        assertNotGrantingAdminUnlessAdmin(principal, request);
        applyTenantScope(principal, request);
        return employeeService.update(id, request);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping("/{id}")
    public EmployeeResponse getById(@PathVariable Long id) {
        return employeeService.getById(id);
    }

    /**
     * Manually overrides basicDA/hra/conveyance/education for this employee,
     * in place of whatever {@code SalaryRule} would otherwise derive - the
     * rest of the salary structure (grossSalary, pfBasic, medical/other
     * allowance) is still edited via the regular {@link #update}. Sticky
     * across future updates and rule changes until {@link
     * #regenerateSalaryStructure} is called.
     */
    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PutMapping("/{id}/salary-structure")
    public EmployeeResponse updateSalaryStructure(@PathVariable Long id,
                                                   @Valid @RequestBody SalaryStructureRequest request) {
        return employeeService.updateSalaryStructure(id, request);
    }

    /**
     * Clears any manual override and recomputes this employee's salary
     * structure from their current gross salary and the company's current
     * {@code SalaryRule} - use after a rule change (or to undo an override)
     * so the structure stops drifting from the formula.
     */
    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PostMapping("/{id}/salary-structure/regenerate")
    public EmployeeResponse regenerateSalaryStructure(@PathVariable Long id) {
        return employeeService.regenerateSalaryStructure(id);
    }

    /**
     * Same regeneration, for every active employee of the caller's company
     * at once - the practical fix for "the salary rule changed but the
     * structure didn't." Employees with an active manual override are
     * skipped; see {@link EmployeeService#regenerateAllSalaryStructures}.
     */
    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PostMapping("/salary-structure/regenerate-all")
    public Map<String, Integer> regenerateAllSalaryStructures() {
        return Map.of("regenerated", employeeService.regenerateAllSalaryStructures());
    }

    /**
     * Records a salary hike/promotion/correction: sets the new gross salary,
     * re-derives the structure from the current {@code SalaryRule} (or, for
     * an overridden employee, applies the replacement structure supplied in
     * the same request - see {@link SalaryRevisionRequest}'s Javadoc), and
     * writes an immutable history row.
     */
    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PostMapping("/{id}/salary-revision")
    public EmployeeResponse reviseSalary(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable Long id,
                                          @Valid @RequestBody SalaryRevisionRequest request) {
        return employeeService.reviseSalary(id, request, principal.getUsername());
    }

    /** Every past revision for this employee, newest effective date first. */
    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping("/{id}/salary-revisions")
    public List<SalaryRevision> getSalaryRevisions(@PathVariable Long id) {
        return employeeService.getSalaryRevisions(id);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping("/by-user-id/{userId}")
    public EmployeeResponse getByUserId(@PathVariable String userId) {
        return employeeService.getByUserId(userId);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping
    public List<EmployeeResponse> getAll() {
        return employeeService.getVisible();
    }

    /** The supervisor's team - the basis of every approval flow. */
    @PreAuthorize("@authz.can('EMPLOYEE_READ')")
    @GetMapping("/{supervisorUserId}/team")
    public List<EmployeeResponse> getTeam(@PathVariable String supervisorUserId) {
        return employeeService.getTeamOf(supervisorUserId);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PatchMapping("/{userId}/supervisor")
    public EmployeeResponse assignSupervisor(@PathVariable String userId,
                                             @RequestParam(required = false) String supervisorUserId) {
        return employeeService.assignSupervisor(userId, supervisorUserId);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_DELETE')")
    @DeleteMapping("/{id}")
    public EmployeeResponse deactivate(@PathVariable Long id) {
        return employeeService.deactivate(id);
    }

    /**
     * Practical stand-in for self-service forgot-password (no email
     * infrastructure exists to build the real thing) - HR/ADMIN generates a
     * new temporary password for an employee who's lost theirs or is
     * locked out. Same permission as every other account-affecting change
     * to this employee, same one-time-return contract as {@code create}.
     */
    /**
     * Clears a failed-login lockout, leaving the password alone - see
     * {@code EmployeeService#unlockAccount} for why this is not just
     * {@code reset-password}.
     */
    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PostMapping("/{id}/unlock")
    public EmployeeResponse unlock(@PathVariable Long id) {
        return employeeService.unlockAccount(id);
    }

    @PreAuthorize("@authz.can('EMPLOYEE_UPDATE')")
    @PostMapping("/{id}/reset-password")
    public EmployeeCreationResponse resetPassword(@PathVariable Long id) {
        return employeeService.resetPassword(id);
    }

    /**
     * Self-escalation guard (HRMS spec §7): granting the ADMIN role is
     * itself an ADMIN-only action, so HR - which otherwise has full
     * EMPLOYEE_CREATE/UPDATE rights - cannot mint a new admin account or
     * promote itself to one. Kept here rather than in EmployeeService so the
     * many existing service-level tests that call
     * {@code EmployeeService.create/update} directly (with no
     * SecurityContext populated) are unaffected.
     */
    private void assertNotGrantingAdminUnlessAdmin(UserPrincipal principal, EmployeeRequest request) {
        if (principal == null) {
            throw new AuthenticationFailedException("Authentication is required");
        }
        if (request.getRole() == Role.ADMIN && !Role.ADMIN.name().equals(principal.getRole())) {
            throw new AccessDeniedException("Only an ADMIN may grant the ADMIN role");
        }
    }

    /**
     * Tenant isolation (create/update side): {@code companyId} is never taken
     * from the request for a company-scoped caller - otherwise HR at Company
     * A could create, or reassign an existing employee to, Company B simply
     * by naming its id. A platform principal never reaches this controller
     * (it holds none of the EMPLOYEE_* permissions), so there is nothing to
     * scope for that case.
     */
    private void applyTenantScope(UserPrincipal principal, EmployeeRequest request) {
        if (principal.getType() == PrincipalType.EMPLOYEE) {
            request.setCompanyId(principal.getCompanyId());
        }
    }
}
