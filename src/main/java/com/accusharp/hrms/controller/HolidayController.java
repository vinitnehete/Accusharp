package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.HolidayRequest;
import com.accusharp.hrms.entity.Holiday;
import com.accusharp.hrms.service.HolidayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    @PreAuthorize("@authz.can('HOLIDAY_MANAGE')")
    @PostMapping
    public ResponseEntity<Holiday> create(@Valid @RequestBody HolidayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holidayService.create(request));
    }

    @PreAuthorize("@authz.can('HOLIDAY_MANAGE')")
    @PutMapping("/{id}")
    public Holiday update(@PathVariable Long id, @Valid @RequestBody HolidayRequest request) {
        return holidayService.update(id, request);
    }

    @PreAuthorize("@authz.can('HOLIDAY_READ')")
    @GetMapping
    public List<Holiday> getAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return (fromDate == null || toDate == null)
                ? holidayService.getAll()
                : holidayService.getBetween(fromDate, toDate);
    }

    @PreAuthorize("@authz.can('HOLIDAY_MANAGE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        holidayService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
