package com.accusharp.hrms.service.payroll;

import com.accusharp.hrms.dto.SalarySlipResponse;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.util.AmountInWords;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the salary slip from an immutable payroll snapshot. Nothing is
 * recalculated here - the slip shows exactly what payroll recorded, which is
 * what makes a reprint reproducible months later.
 */
@Service
@RequiredArgsConstructor
public class SalarySlipService {

    private static final DateTimeFormatter GENERATED_AT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withLocale(Locale.ENGLISH)
                    .withZone(java.time.ZoneId.systemDefault());

    private final PayrollService payrollService;

    @Transactional(readOnly = true)
    public SalarySlipResponse getSlip(String employeeId, int month, int year) {
        return toSlip(payrollService.getCurrent(employeeId, month, year));
    }

    @Transactional(readOnly = true)
    public SalarySlipResponse getSlipById(Long payrollId) {
        return toSlip(payrollService.getById(payrollId));
    }

    @Transactional(readOnly = true)
    public List<SalarySlipResponse> getSlipsForPeriod(int month, int year) {
        return payrollService.getPeriod(month, year).stream().map(this::toSlip).toList();
    }

    public SalarySlipResponse toSlip(Payroll payroll) {
        List<SalarySlipResponse.Line> earnings = new ArrayList<>();
        addLine(earnings, "Basic + DA", payroll.getEarnBasicDA());
        addLine(earnings, "HRA", payroll.getEarnHra());
        addLine(earnings, "Conveyance Allowance", payroll.getEarnConveyance());
        addLine(earnings, "Education Allowance", payroll.getEarnEducation());
        addLine(earnings, "Medical Allowance", payroll.getEarnMedical());
        addLine(earnings, "Other Allowance", payroll.getEarnOther());
        addLine(earnings, "Overtime Allowance", payroll.getOtAllowance());
        addLine(earnings, "Bonus", payroll.getBonus());
        addLine(earnings, "Incentive", payroll.getIncentive());

        List<SalarySlipResponse.Line> deductions = new ArrayList<>();
        addLine(deductions, "Provident Fund", payroll.getPfDeduction());
        addLine(deductions, "ESIC", payroll.getEsic());
        addLine(deductions, "Professional Tax", payroll.getProfessionalTax());
        addLine(deductions, "TDS", payroll.getTds());
        addLine(deductions, "Advance", payroll.getAdvanceDeduction());
        addLine(deductions, "Loan", payroll.getLoanDeduction());
        addLine(deductions, "Canteen", payroll.getCanteen());

        SalarySlipResponse.AttendanceSummary attendance = new SalarySlipResponse.AttendanceSummary(
                payroll.getDaysInMonth(), payroll.getWorkingDays(), payroll.getPresentDays(),
                payroll.getPaidLeaveDays(), payroll.getLopDays(), payroll.getPayableDays(),
                payroll.getTotalHours(), payroll.getOvertimeHours());

        return new SalarySlipResponse(
                payroll.getCompanyName(),
                payroll.getEmployeeId(),
                payroll.getEmployeeCode(),
                payroll.getEmployeeName(),
                payroll.getDepartmentName(),
                payroll.getDesignationName(),
                periodLabel(payroll),
                attendance,
                earnings,
                deductions,
                payroll.getTotalEarnings(),
                payroll.getTotalDeduction(),
                payroll.getNetSalary(),
                AmountInWords.convert(payroll.getNetSalary()),
                payroll.getRevision(),
                payroll.getGeneratedAt());
    }

    /**
     * Print-ready slip. Self-contained HTML so it can be opened, printed or
     * saved as PDF from any browser without a server-side PDF dependency.
     */
    @Transactional(readOnly = true)
    public String renderHtml(String employeeId, int month, int year) {
        SalarySlipResponse slip = getSlip(employeeId, month, year);
        StringBuilder html = new StringBuilder(4096);

        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>Salary Slip - ").append(escape(slip.employeeName()))
                .append(" - ").append(escape(slip.period())).append("</title><style>")
                .append("body{font-family:Arial,Helvetica,sans-serif;margin:24px;color:#222}")
                .append("h1{font-size:18px;margin:0 0 4px}h2{font-size:13px;margin:18px 0 6px}")
                .append(".muted{color:#666;font-size:12px}")
                .append("table{border-collapse:collapse;width:100%;margin-top:6px;font-size:12px}")
                .append("th,td{border:1px solid #ccc;padding:6px 8px;text-align:left}")
                .append("th{background:#f2f2f2}td.amt{text-align:right}")
                .append(".net{margin-top:14px;font-size:14px;font-weight:bold}")
                .append("@media print{body{margin:0}}")
                .append("</style></head><body>");

        html.append("<h1>").append(escape(orDash(slip.companyName()))).append("</h1>")
                .append("<div class=\"muted\">Salary Slip for ").append(escape(slip.period()))
                .append(" &middot; revision ").append(slip.revision())
                .append(" &middot; generated ").append(GENERATED_AT.format(slip.generatedAt()))
                .append("</div>");

        html.append("<h2>Employee</h2><table>")
                .append(row("Employee ID", slip.employeeId()))
                .append(row("Employee Code", slip.employeeCode()))
                .append(row("Name", slip.employeeName()))
                .append(row("Department", slip.departmentName()))
                .append(row("Designation", slip.designationName()))
                .append("</table>");

        SalarySlipResponse.AttendanceSummary a = slip.attendance();
        html.append("<h2>Attendance</h2><table>")
                .append(row("Days in Month", String.valueOf(a.daysInMonth())))
                .append(row("Working Days", String.valueOf(a.workingDays())))
                .append(row("Present Days", String.valueOf(a.presentDays())))
                .append(row("Paid Leave Days", String.valueOf(a.paidLeaveDays())))
                .append(row("LOP Days", String.valueOf(a.lopDays())))
                .append(row("Payable Days", String.valueOf(a.payableDays())))
                .append(row("Total Hours", String.valueOf(a.totalHours())))
                .append(row("Overtime Hours", String.valueOf(a.overtimeHours())))
                .append("</table>");

        html.append("<h2>Earnings</h2>").append(amountTable(slip.earnings(), "Total Earnings",
                slip.totalEarnings()));
        html.append("<h2>Deductions</h2>").append(amountTable(slip.deductions(), "Total Deductions",
                slip.totalDeductions()));

        html.append("<div class=\"net\">Net Salary: ").append(slip.netSalary()).append("</div>")
                .append("<div class=\"muted\">").append(escape(slip.netSalaryInWords())).append("</div>")
                .append("<p class=\"muted\">This is a system generated salary slip.</p>")
                .append("</body></html>");

        return html.toString();
    }

    /** Spreadsheet-friendly export of a whole period. */
    @Transactional(readOnly = true)
    public String renderPeriodCsv(int month, int year) {
        StringBuilder csv = new StringBuilder(2048);
        csv.append("employeeId,employeeCode,employeeName,department,designation,workingDays,presentDays,")
                .append("paidLeaveDays,lopDays,payableDays,totalEarnings,totalDeductions,netSalary\n");

        for (Payroll payroll : payrollService.getPeriod(month, year)) {
            csv.append(csvCell(payroll.getEmployeeId())).append(',')
                    .append(csvCell(payroll.getEmployeeCode())).append(',')
                    .append(csvCell(payroll.getEmployeeName())).append(',')
                    .append(csvCell(payroll.getDepartmentName())).append(',')
                    .append(csvCell(payroll.getDesignationName())).append(',')
                    .append(payroll.getWorkingDays()).append(',')
                    .append(payroll.getPresentDays()).append(',')
                    .append(payroll.getPaidLeaveDays()).append(',')
                    .append(payroll.getLopDays()).append(',')
                    .append(payroll.getPayableDays()).append(',')
                    .append(payroll.getTotalEarnings()).append(',')
                    .append(payroll.getTotalDeduction()).append(',')
                    .append(payroll.getNetSalary()).append('\n');
        }
        return csv.toString();
    }

    // ---- helpers -----------------------------------------------------------

    private void addLine(List<SalarySlipResponse.Line> lines, String label, BigDecimal amount) {
        if (amount != null && amount.signum() != 0) {
            lines.add(new SalarySlipResponse.Line(label, amount));
        }
    }

    private String periodLabel(Payroll payroll) {
        YearMonth period = YearMonth.of(payroll.getYear(), payroll.getMonth());
        return period.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + period.getYear();
    }

    private String amountTable(List<SalarySlipResponse.Line> lines, String totalLabel, BigDecimal total) {
        StringBuilder table = new StringBuilder("<table><tr><th>Component</th><th>Amount</th></tr>");
        for (SalarySlipResponse.Line line : lines) {
            table.append("<tr><td>").append(escape(line.label())).append("</td><td class=\"amt\">")
                    .append(line.amount()).append("</td></tr>");
        }
        table.append("<tr><th>").append(escape(totalLabel)).append("</th><th class=\"amt\">")
                .append(total).append("</th></tr></table>");
        return table.toString();
    }

    private String row(String label, String value) {
        return "<tr><td>" + escape(label) + "</td><td>" + escape(orDash(value)) + "</td></tr>";
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\"", "\"\"");
        return cleaned.contains(",") ? "\"" + cleaned + "\"" : cleaned;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
