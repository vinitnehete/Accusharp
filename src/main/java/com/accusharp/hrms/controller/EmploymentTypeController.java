package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.EmploymentTypeRequest;
import com.accusharp.hrms.entity.EmploymentType;
import com.accusharp.hrms.service.EmploymentTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Employment types and the payroll behaviour attached to them.
 *
 * <p>This is what makes "day-wise employees are capped at 26 days and their
 * extra hours become overtime" a row a company can edit rather than a constant
 * in a Java enum. A company can define as many types as it needs, each picking
 * a pay basis and carrying its own numbers.
 *
 * <p>Assigning a type to an employee is optional. An employee with none falls
 * back to the legacy {@code EmployeeStatus} semantics, which is exactly what
 * every existing row does - so adopting this is opt-in per employee rather than
 * a migration that must finish before the next payroll run.
 */
@RestController
@RequestMapping("/api/employment-types")
@RequiredArgsConstructor
public class EmploymentTypeController {

    private final EmploymentTypeService employmentTypeService;

    /** The caller's own company's types plus the shared catalog. */
    @PreAuthorize("@authz.can('EMPLOYMENT_TYPE_READ')")
    @GetMapping
    public List<EmploymentType> getAll() {
        return employmentTypeService.getAll();
    }

    @PreAuthorize("@authz.can('EMPLOYMENT_TYPE_READ')")
    @GetMapping("/{id}")
    public EmploymentType getById(@PathVariable Long id) {
        return employmentTypeService.getById(id);
    }

    /**
     * Creates the four types that reproduce today's built-in behaviour exactly -
     * a correct starting point to customise from rather than a blank page.
     * Idempotent, and never overwrites a type that already exists.
     */
    @PreAuthorize("@authz.can('EMPLOYMENT_TYPE_MANAGE')")
    @PostMapping("/seed-defaults")
    public List<EmploymentType> seedDefaults() {
        return employmentTypeService.seedDefaults();
    }

    @PreAuthorize("@authz.can('EMPLOYMENT_TYPE_MANAGE')")
    @PostMapping
    public EmploymentType create(@Valid @RequestBody EmploymentTypeRequest request) {
        return employmentTypeService.create(request);
    }

    /**
     * Edits a type in place. Payroll already generated under it is unaffected:
     * {@code Payroll} snapshots the pay basis it was computed with, so an edit
     * changes future runs only.
     */
    @PreAuthorize("@authz.can('EMPLOYMENT_TYPE_MANAGE')")
    @PutMapping("/{id}")
    public EmploymentType update(@PathVariable Long id, @Valid @RequestBody EmploymentTypeRequest request) {
        return employmentTypeService.update(id, request);
    }

    /** Refused once anybody is on the type - set {@code active: false} instead. */
    @PreAuthorize("@authz.can('EMPLOYMENT_TYPE_MANAGE')")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        employmentTypeService.delete(id);
    }
}
