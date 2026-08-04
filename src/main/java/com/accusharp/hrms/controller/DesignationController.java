package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.DesignationRequest;
import com.accusharp.hrms.entity.Designation;
import com.accusharp.hrms.service.DesignationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/designations")
@RequiredArgsConstructor
public class DesignationController {

    private final DesignationService designationService;

    @PostMapping
    public ResponseEntity<Designation> create(@Valid @RequestBody DesignationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(designationService.create(request));
    }

    @PutMapping("/{id}")
    public Designation update(@PathVariable Long id, @Valid @RequestBody DesignationRequest request) {
        return designationService.update(id, request);
    }

    @GetMapping("/{id}")
    public Designation getById(@PathVariable Long id) {
        return designationService.getById(id);
    }

    @GetMapping
    public List<Designation> getAll() {
        return designationService.getAll();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        designationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
