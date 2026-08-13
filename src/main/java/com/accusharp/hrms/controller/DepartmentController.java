package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.DepartmentRequest;
import com.accusharp.hrms.entity.Department;
import com.accusharp.hrms.service.DepartmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @PreAuthorize("@authz.can('DEPARTMENT_MANAGE')")
    @PostMapping
    public ResponseEntity<Department> create(@Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(departmentService.create(request));
    }

    @PreAuthorize("@authz.can('DEPARTMENT_MANAGE')")
    @PutMapping("/{id}")
    public Department update(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        return departmentService.update(id, request);
    }

    @PreAuthorize("@authz.can('DEPARTMENT_READ')")
    @GetMapping("/{id}")
    public Department getById(@PathVariable Long id) {
        return departmentService.getById(id);
    }

    @PreAuthorize("@authz.can('DEPARTMENT_READ')")
    @GetMapping
    public List<Department> getAll() {
        return departmentService.getAll();
    }

    @PreAuthorize("@authz.can('DEPARTMENT_MANAGE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        departmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
