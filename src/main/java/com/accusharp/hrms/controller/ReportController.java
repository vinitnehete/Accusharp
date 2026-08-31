package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.EmployeeResponse;
import com.accusharp.hrms.dto.PayrollAuditDtos;
import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.dto.ReportFilter;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.report.AttendanceLeaveReportService;
import com.accusharp.hrms.service.report.PayrollAuditService;
import com.accusharp.hrms.service.report.PayrollRegisterService;
import com.accusharp.hrms.service.report.ReportService;
import com.accusharp.hrms.service.report.StatutoryReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final PayrollAuditService payrollAuditService;
    private final PayrollRegisterService payrollRegisterService;
    private final StatutoryReportService statutoryReportService;
    private final AttendanceLeaveReportService attendanceLeaveReportService;

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
    public List<ReportDtos.PayrollRow> payroll(@RequestParam @Min(1) @Max(12) int month, @RequestParam @Min(2000) @Max(2100) int year) {
        return reportService.payrollReport(month, year);
    }

    @GetMapping("/payroll/by-department")
    public List<ReportDtos.PayrollCostGroup> departmentPayroll(@RequestParam @Min(1) @Max(12) int month,
                                                               @RequestParam @Min(2000) @Max(2100) int year) {
        return reportService.departmentPayrollReport(month, year);
    }

    @GetMapping("/payroll/by-company")
    public List<ReportDtos.PayrollCostGroup> companyPayroll(@RequestParam @Min(1) @Max(12) int month,
                                                            @RequestParam @Min(2000) @Max(2100) int year) {
        return reportService.companyPayrollReport(month, year);
    }

    @GetMapping("/statutory/pf")
    public List<ReportDtos.StatutoryRow> pf(@RequestParam @Min(1) @Max(12) int month, @RequestParam @Min(2000) @Max(2100) int year) {
        return reportService.pfReport(month, year);
    }

    @GetMapping("/statutory/professional-tax")
    public List<ReportDtos.StatutoryRow> professionalTax(@RequestParam @Min(1) @Max(12) int month, @RequestParam @Min(2000) @Max(2100) int year) {
        return reportService.professionalTaxReport(month, year);
    }

    @GetMapping("/statutory/esic")
    public List<ReportDtos.StatutoryRow> esic(@RequestParam @Min(1) @Max(12) int month, @RequestParam @Min(2000) @Max(2100) int year) {
        return reportService.esicReport(month, year);
    }

    // ---- monthly payroll audit ---------------------------------------------

    /** One reconcilable line per employee: identifiers, fixed wages, earned wages, OT, hours, deductions, net. */
    @GetMapping("/payroll/audit")
    public List<PayrollAuditDtos.AuditRow> payrollAudit(@RequestParam @Min(1) @Max(12) int month,
                                                        @RequestParam @Min(2000) @Max(2100) int year,
                                                        @RequestParam(required = false) Long departmentId,
                                                        @RequestParam(required = false) Long designationId,
                                                        @RequestParam(required = false) Long categoryId) {
        return payrollAuditService.auditReport(month, year, filter(departmentId, designationId, categoryId));
    }

    /** Company-level totals over exactly the population {@link #payrollAudit} lists. */
    @GetMapping("/payroll/audit/summary")
    public PayrollAuditDtos.CompanySummary payrollAuditSummary(@RequestParam @Min(1) @Max(12) int month,
                                                               @RequestParam @Min(2000) @Max(2100) int year,
                                                               @RequestParam(required = false) Long departmentId,
                                                               @RequestParam(required = false) Long designationId,
                                                               @RequestParam(required = false) Long categoryId) {
        return payrollAuditService.companySummary(month, year, filter(departmentId, designationId, categoryId));
    }

    /** The day-by-day attendance and wage table behind one employee's audit line. */
    @GetMapping("/payroll/audit/{employeeId}/days")
    public PayrollAuditDtos.DayWiseReport payrollAuditDays(@PathVariable String employeeId,
                                                           @RequestParam @Min(1) @Max(12) int month,
                                                           @RequestParam @Min(2000) @Max(2100) int year) {
        return payrollAuditService.dayWiseReport(employeeId, month, year);
    }

    @GetMapping(value = "/payroll/audit/export", produces = "text/csv")
    public ResponseEntity<String> payrollAuditExport(@RequestParam @Min(1) @Max(12) int month,
                                                     @RequestParam @Min(2000) @Max(2100) int year,
                                                     @RequestParam(required = false) Long departmentId,
                                                     @RequestParam(required = false) Long designationId,
                                                     @RequestParam(required = false) Long categoryId) {
        return csv(payrollAuditService.auditCsv(month, year, filter(departmentId, designationId, categoryId)),
                "payroll-audit-%d-%02d.csv".formatted(year, month));
    }

    // ---- payroll registers --------------------------------------------------

    /** Full CTC breakup and every earning/deduction head, one row per employee for the run. */
    @GetMapping("/payroll/register")
    public List<ReportDtos.PayrollRegisterRow> payrollRegister(@RequestParam @Min(1) @Max(12) int month,
                                                               @RequestParam @Min(2000) @Max(2100) int year,
                                                               @RequestParam(required = false) Long departmentId,
                                                               @RequestParam(required = false) Long designationId,
                                                               @RequestParam(required = false) Long categoryId) {
        return payrollRegisterService.payrollRegister(month, year,
                filter(departmentId, designationId, categoryId));
    }

    @GetMapping(value = "/payroll/register/export", produces = "text/csv")
    public ResponseEntity<String> payrollRegisterExport(@RequestParam @Min(1) @Max(12) int month,
                                                        @RequestParam @Min(2000) @Max(2100) int year,
                                                        @RequestParam(required = false) Long departmentId,
                                                        @RequestParam(required = false) Long designationId,
                                                        @RequestParam(required = false) Long categoryId) {
        return csv(payrollRegisterService.payrollRegisterCsv(month, year,
                        filter(departmentId, designationId, categoryId)),
                "payroll-register-%d-%02d.csv".formatted(year, month));
    }

    /** Every employee's payslip totals for the period, in bulk. */
    @GetMapping("/payroll/payslip-register")
    public List<ReportDtos.PayslipRegisterRow> payslipRegister(@RequestParam @Min(1) @Max(12) int month,
                                                               @RequestParam @Min(2000) @Max(2100) int year,
                                                               @RequestParam(required = false) Long departmentId,
                                                               @RequestParam(required = false) Long designationId,
                                                               @RequestParam(required = false) Long categoryId) {
        return payrollRegisterService.payslipRegister(month, year,
                filter(departmentId, designationId, categoryId));
    }

    /** Salary revisions taking effect in the window, with the arrears exposure each created. */
    @GetMapping("/payroll/salary-revisions")
    public List<ReportDtos.SalaryRevisionRow> salaryRevisions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return payrollRegisterService.salaryRevisionReport(from, to,
                filter(departmentId, designationId, categoryId));
    }

    /** The bank credit advice for a period, with the control totals a bank file is reconciled against. */
    @GetMapping("/payroll/bank-transfer")
    public ReportDtos.BankTransferAdvice bankTransfer(@RequestParam @Min(1) @Max(12) int month,
                                                      @RequestParam @Min(2000) @Max(2100) int year,
                                                      @RequestParam(required = false) Long departmentId,
                                                      @RequestParam(required = false) Long designationId,
                                                      @RequestParam(required = false) Long categoryId) {
        return payrollRegisterService.bankTransferAdvice(month, year,
                filter(departmentId, designationId, categoryId));
    }

    @GetMapping(value = "/payroll/bank-transfer/export", produces = "text/csv")
    public ResponseEntity<String> bankTransferExport(@RequestParam @Min(1) @Max(12) int month,
                                                     @RequestParam @Min(2000) @Max(2100) int year,
                                                     @RequestParam(required = false) Long departmentId,
                                                     @RequestParam(required = false) Long designationId,
                                                     @RequestParam(required = false) Long categoryId) {
        return csv(payrollRegisterService.bankTransferCsv(month, year,
                        filter(departmentId, designationId, categoryId)),
                "bank-advice-%d-%02d.csv".formatted(year, month));
    }

    /** The full and final worksheet for everyone relieved inside the window. */
    @GetMapping("/payroll/full-and-final")
    public List<ReportDtos.FullAndFinalRow> fullAndFinal(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return payrollRegisterService.fullAndFinalReport(from, to,
                filter(departmentId, designationId, categoryId));
    }

    // ---- attendance & leave drill-downs ------------------------------------

    /** Every day in the month that needs explaining - late in, early out, missed punch, unexplained absence. */
    @GetMapping("/attendance/exceptions")
    public List<ReportDtos.AttendanceExceptionRow> attendanceExceptions(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return attendanceLeaveReportService.attendanceExceptionReport(month,
                filter(departmentId, designationId, categoryId));
    }

    @GetMapping(value = "/attendance/exceptions/export", produces = "text/csv")
    public ResponseEntity<String> attendanceExceptionsExport(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return csv(attendanceLeaveReportService.attendanceExceptionCsv(month,
                        filter(departmentId, designationId, categoryId)),
                "attendance-exceptions-%s.csv".formatted(month));
    }

    /** Day-level overtime drill-down, priced at the rate the period's payroll actually used. */
    @GetMapping("/attendance/overtime-register")
    public List<ReportDtos.OvertimeRegisterRow> overtimeRegister(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return attendanceLeaveReportService.overtimeRegister(month,
                filter(departmentId, designationId, categoryId));
    }

    @GetMapping(value = "/attendance/overtime-register/export", produces = "text/csv")
    public ResponseEntity<String> overtimeRegisterExport(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return csv(attendanceLeaveReportService.overtimeRegisterCsv(month,
                        filter(departmentId, designationId, categoryId)),
                "overtime-register-%s.csv".formatted(month));
    }

    /** Every leave request overlapping the window, whatever became of it. */
    @GetMapping("/leave/transactions")
    public List<ReportDtos.LeaveTransactionRow> leaveTransactions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return attendanceLeaveReportService.leaveTransactionReport(from, to,
                filter(departmentId, designationId, categoryId));
    }

    @GetMapping(value = "/leave/transactions/export", produces = "text/csv")
    public ResponseEntity<String> leaveTransactionsExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return csv(attendanceLeaveReportService.leaveTransactionCsv(from, to,
                        filter(departmentId, designationId, categoryId)),
                "leave-transactions-%s-to-%s.csv".formatted(from, to));
    }

    // ---- statutory filings --------------------------------------------------

    /** PF ECR lines for the period, in the EPFO column order. */
    @GetMapping("/statutory/pf-ecr")
    public List<ReportDtos.PfEcrRow> pfEcr(@RequestParam @Min(1) @Max(12) int month,
                                           @RequestParam @Min(2000) @Max(2100) int year,
                                           @RequestParam(required = false) Long departmentId,
                                           @RequestParam(required = false) Long designationId,
                                           @RequestParam(required = false) Long categoryId) {
        return statutoryReportService.pfEcrReport(month, year, filter(departmentId, designationId, categoryId));
    }

    /** ESI monthly return lines for the period. */
    @GetMapping("/statutory/esi-return")
    public List<ReportDtos.EsiReturnRow> esiReturn(@RequestParam @Min(1) @Max(12) int month,
                                                   @RequestParam @Min(2000) @Max(2100) int year,
                                                   @RequestParam(required = false) Long departmentId,
                                                   @RequestParam(required = false) Long designationId,
                                                   @RequestParam(required = false) Long categoryId) {
        return statutoryReportService.esiReturnReport(month, year, filter(departmentId, designationId, categoryId));
    }

    /** Professional tax register - who was taxed, on what, and how much. */
    @GetMapping("/statutory/pt-register")
    public List<ReportDtos.ProfessionalTaxRow> professionalTaxRegister(
            @RequestParam @Min(1) @Max(12) int month,
            @RequestParam @Min(2000) @Max(2100) int year,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return statutoryReportService.professionalTaxRegister(month, year,
                filter(departmentId, designationId, categoryId));
    }

    /** Quarterly TDS position per employee - the figures Form 24Q Annexure I is filed from. */
    @GetMapping("/statutory/tds-24q")
    public List<ReportDtos.Tds24qRow> tds24q(@RequestParam @Min(2000) @Max(2100) int financialYear,
                                             @RequestParam @Min(1) @Max(4) int quarter,
                                             @RequestParam(required = false) Long departmentId,
                                             @RequestParam(required = false) Long designationId,
                                             @RequestParam(required = false) Long categoryId) {
        return statutoryReportService.tds24qReport(financialYear, quarter,
                filter(departmentId, designationId, categoryId));
    }

    /** Gratuity liability accrued per employee as at a date. */
    @GetMapping("/statutory/gratuity")
    public List<ReportDtos.GratuityAccrualRow> gratuity(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long categoryId) {
        return statutoryReportService.gratuityAccrualReport(asOf == null ? LocalDate.now() : asOf,
                filter(departmentId, designationId, categoryId));
    }

    // ---- helpers ------------------------------------------------------------

    private ReportFilter filter(Long departmentId, Long designationId, Long categoryId) {
        return ReportFilter.of(departmentId, designationId, categoryId);
    }

    /** Same download shape {@code SalarySlipController.exportCsv} uses, so every export behaves alike. */
    private ResponseEntity<String> csv(String body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
