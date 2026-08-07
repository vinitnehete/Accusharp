package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.SalarySlipResponse;
import com.accusharp.hrms.service.payroll.SalarySlipService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/salary-slips")
@RequiredArgsConstructor
@PreAuthorize("@authz.can('SALARY_SLIP_READ')")
public class SalarySlipController {

    private final SalarySlipService salarySlipService;

    @GetMapping("/{employeeId}")
    public SalarySlipResponse getSlip(@PathVariable String employeeId,
                                      @RequestParam int month,
                                      @RequestParam int year) {
        return salarySlipService.getSlip(employeeId, month, year);
    }

    @GetMapping
    public List<SalarySlipResponse> getPeriodSlips(@RequestParam int month, @RequestParam int year) {
        return salarySlipService.getSlipsForPeriod(month, year);
    }

    /** Print-ready slip - open in a browser and print or save as PDF. */
    @GetMapping(value = "/{employeeId}/print", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> print(@PathVariable String employeeId,
                                        @RequestParam int month,
                                        @RequestParam int year) {
        String html = salarySlipService.renderHtml(employeeId, month, year);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(html);
    }

    /** Spreadsheet export of the whole period. */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> exportCsv(@RequestParam int month, @RequestParam int year) {
        String csv = salarySlipService.renderPeriodCsv(month, year);
        String filename = "salary-slips-%d-%02d.csv".formatted(year, month);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
