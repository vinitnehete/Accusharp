package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.DesignationRequest;
import com.accusharp.hrms.entity.Designation;
import com.accusharp.hrms.service.DesignationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/designations")
@RequiredArgsConstructor
public class DesignationController {

    private final DesignationService designationService;

    @PreAuthorize("@authz.can('DESIGNATION_MANAGE')")
    @PostMapping
    public ResponseEntity<Designation> create(@Valid @RequestBody DesignationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(designationService.create(request));
    }

    @PreAuthorize("@authz.can('DESIGNATION_MANAGE')")
    @PutMapping("/{id}")
    public Designation update(@PathVariable Long id, @Valid @RequestBody DesignationRequest request) {
        return designationService.update(id, request);
    }

    @PreAuthorize("@authz.can('DESIGNATION_READ')")
    @GetMapping("/{id}")
    public Designation getById(@PathVariable Long id) {
        return designationService.getById(id);
    }

    @PreAuthorize("@authz.can('DESIGNATION_READ')")
    @GetMapping
    public List<Designation> getAll() {
        return designationService.getAll();
    }

    @PreAuthorize("@authz.can('DESIGNATION_MANAGE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        designationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
