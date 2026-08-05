package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.ShiftRequest;
import com.accusharp.hrms.dto.ShiftResponse;
import com.accusharp.hrms.service.shift.ShiftService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Shift master - the definitions the roster points at.
 *
 * <p>Responses carry {@code crossesMidnight} and {@code warnings} alongside the
 * stored fields, because whether a shift crosses midnight is inferred from the
 * times rather than set, and a shift misconfigured in that respect is otherwise
 * indistinguishable from a correct one until the attendance comes out wrong.
 */
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    @PostMapping
    public ResponseEntity<ShiftResponse> create(@Valid @RequestBody ShiftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ShiftResponse.of(shiftService.create(request)));
    }

    @PutMapping("/{id}")
    public ShiftResponse update(@PathVariable Long id, @Valid @RequestBody ShiftRequest request) {
        return ShiftResponse.of(shiftService.update(id, request));
    }

    @GetMapping("/{id}")
    public ShiftResponse getById(@PathVariable Long id) {
        return ShiftResponse.of(shiftService.getById(id));
    }

    @GetMapping
    public List<ShiftResponse> getAll() {
        return shiftService.getAll().stream().map(ShiftResponse::of).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        shiftService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
