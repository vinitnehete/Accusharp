package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.EmployeeRequest;
import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @PostMapping
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody EmployeeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.create(request));
    }

    @PutMapping("/{id}")
    public EmployeeResponse update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        return employeeService.update(id, request);
    }

    @GetMapping("/{id}")
    public EmployeeResponse getById(@PathVariable Long id) {
        return employeeService.getById(id);
    }

    @GetMapping("/by-user-id/{userId}")
    public EmployeeResponse getByUserId(@PathVariable String userId) {
        return employeeService.getByUserId(userId);
    }

    @GetMapping
    public List<EmployeeResponse> getAll() {
        return employeeService.getAll();
    }

    /** The supervisor's team - the basis of every approval flow. */
    @GetMapping("/{supervisorUserId}/team")
    public List<EmployeeResponse> getTeam(@PathVariable String supervisorUserId) {
        return employeeService.getTeamOf(supervisorUserId);
    }

    @PatchMapping("/{userId}/supervisor")
    public EmployeeResponse assignSupervisor(@PathVariable String userId,
                                             @RequestParam(required = false) String supervisorUserId) {
        return employeeService.assignSupervisor(userId, supervisorUserId);
    }

    @DeleteMapping("/{id}")
    public EmployeeResponse deactivate(@PathVariable Long id) {
        return employeeService.deactivate(id);
    }
}
