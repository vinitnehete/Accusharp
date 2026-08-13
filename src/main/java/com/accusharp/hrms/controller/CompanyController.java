package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.CompanyOnboardingRequest;
import com.accusharp.hrms.dto.CompanyOnboardingResponse;
import com.accusharp.hrms.dto.CompanyRequest;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.service.CompanyOnboardingService;
import com.accusharp.hrms.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Company onboarding is platform-level (see SECURITY.md); reads are open to any authenticated principal. */
@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;
    private final CompanyOnboardingService companyOnboardingService;

    @PreAuthorize("@authz.can('COMPANY_CREATE')")
    @PostMapping
    public ResponseEntity<Company> create(@Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyService.create(request));
    }

    /**
     * Creates the company and its first ADMIN employee together - a bare
     * {@code POST /api/companies} leaves a company with no employees, and
     * therefore nobody able to create one (EMPLOYEE_CREATE is company-scoped,
     * held only by that company's own ADMIN/HR).
     */
    @PreAuthorize("@authz.can('COMPANY_CREATE')")
    @PostMapping("/onboard")
    public ResponseEntity<CompanyOnboardingResponse> onboard(@Valid @RequestBody CompanyOnboardingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyOnboardingService.onboard(request));
    }

    @PreAuthorize("@authz.can('COMPANY_UPDATE')")
    @PutMapping("/{id}")
    public Company update(@PathVariable Long id, @Valid @RequestBody CompanyRequest request) {
        return companyService.update(id, request);
    }

    @PreAuthorize("@authz.can('COMPANY_READ')")
    @GetMapping("/{id}")
    public Company getById(@PathVariable Long id) {
        return companyService.getById(id);
    }

    @PreAuthorize("@authz.can('COMPANY_READ')")
    @GetMapping
    public List<Company> getAll() {
        return companyService.getAll();
    }

    @PreAuthorize("@authz.can('COMPANY_DELETE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        companyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
