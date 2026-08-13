package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.report.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@PreAuthorize("@authz.can('REPORT_READ')")
public class ReportController {

    private final ReportService reportService;
    private final EmployeeService employeeService;

    @GetMapping("/employees")
    public List<EmployeeResponse> employeeReport() {
        return employeeService.getAll();
    }

    @GetMapping("/attendance/monthly")
    public List<ReportDtos.MonthlyAttendanceRow> monthlyAttendance(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return reportService.monthlyAttendanceReport(month);
    }

    @GetMapping("/attendance/late-coming")
    public List<ReportDtos.ExceptionRow> lateComing(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return reportService.lateComingReport(month);
    }

    @GetMapping("/attendance/absent")
    public List<ReportDtos.ExceptionRow> absent(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return reportService.absentReport(month);
    }

    @GetMapping("/attendance/overtime")
    public List<ReportDtos.ExceptionRow> overtime(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return reportService.overtimeReport(month);
    }

    @GetMapping("/attendance/lop")
    public List<ReportDtos.ExceptionRow> lop(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return reportService.lopReport(month);
    }

    @GetMapping("/leave-balances")
    public List<ReportDtos.LeaveBalanceRow> leaveBalances(
            @RequestParam(required = false) Integer year) {
        return reportService.leaveBalanceReport(year == null ? LocalDate.now().getYear() : year);
    }

    @GetMapping("/payroll")
    public List<ReportDtos.PayrollRow> payroll(@RequestParam int month, @RequestParam int year) {
        return reportService.payrollReport(month, year);
    }

    @GetMapping("/payroll/by-department")
    public List<ReportDtos.PayrollCostGroup> departmentPayroll(@RequestParam int month,
                                                               @RequestParam int year) {
        return reportService.departmentPayrollReport(month, year);
    }

    @GetMapping("/payroll/by-company")
    public List<ReportDtos.PayrollCostGroup> companyPayroll(@RequestParam int month,
                                                            @RequestParam int year) {
        return reportService.companyPayrollReport(month, year);
    }

    @GetMapping("/statutory/pf")
    public List<ReportDtos.StatutoryRow> pf(@RequestParam int month, @RequestParam int year) {
        return reportService.pfReport(month, year);
    }

    @GetMapping("/statutory/professional-tax")
    public List<ReportDtos.StatutoryRow> professionalTax(@RequestParam int month, @RequestParam int year) {
        return reportService.professionalTaxReport(month, year);
    }

    @GetMapping("/statutory/esic")
    public List<ReportDtos.StatutoryRow> esic(@RequestParam int month, @RequestParam int year) {
        return reportService.esicReport(month, year);
    }
}
