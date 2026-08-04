package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.ShiftRequest;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.service.shift.ShiftService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Shift master - the definitions the roster points at. */
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    @PostMapping
    public ResponseEntity<Shift> create(@Valid @RequestBody ShiftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shiftService.create(request));
    }

    @PutMapping("/{id}")
    public Shift update(@PathVariable Long id, @Valid @RequestBody ShiftRequest request) {
        return shiftService.update(id, request);
    }

    @GetMapping("/{id}")
    public Shift getById(@PathVariable Long id) {
        return shiftService.getById(id);
    }

    @GetMapping
    public List<Shift> getAll() {
        return shiftService.getAll();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        shiftService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
