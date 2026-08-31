package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.ReportDtos;
import com.accusharp.hrms.dto.ReportFilter;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.service.payroll.PayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The statutory filing reports: PF ECR, the ESI monthly return, the
 * professional tax register, quarterly TDS (Form 24Q Annexure I) data and
 * gratuity accrual.
 *
 * <p>Employee-side figures are read off the {@link Payroll} snapshot and are
 * exactly what was deducted - {@code pfDeduction}, {@code esic},
 * {@code professionalTax} and {@code tds} all come from
 * {@code DeductionCalculationService} at generation time and are never
 * recomputed here.
 *
 * <p>The <em>employer</em> shares and the gratuity formula are a different
 * matter: this system models only what the employee pays. {@code SalaryRule}
 * has no employer-contribution or gratuity configuration, so the constants
 * below carry the statutory defaults, isolated in one place and named, rather
 * than being scattered through the report bodies. Moving them into
 * {@code SalaryRule} is the natural next step the moment a company needs to
 * differ from them.
 */
@Service
@RequiredArgsConstructor
public class StatutoryReportService {

    private static final int SCALE = 2;
    private static final int DAY_SCALE = 1;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** EPS is 8.33% of EPF wages, capped at the statutory pensionable-wage ceiling. */
    private static final BigDecimal EPS_PERCENT = new BigDecimal("8.33");

    /** Pensionable wage ceiling for EPS and EDLI (Rs 15,000 as notified). */
    private static final BigDecimal PENSION_WAGE_CEILING = new BigDecimal("15000");

    /** Employer's ESI share, against the employee's own rate in {@code SalaryRule.esicPercent}. */
    private static final BigDecimal ESI_EMPLOYER_PERCENT = new BigDecimal("3.25");

    /** Payment of Gratuity Act, 1972: 15 days' wages for every completed year, on a 26-day month. */
    private static final BigDecimal GRATUITY_DAYS_PER_YEAR = new BigDecimal("15");
    private static final BigDecimal GRATUITY_MONTH_DAYS = new BigDecimal("26");

    /** Continuous service required before any gratuity is payable. */
    public static final int GRATUITY_ELIGIBLE_YEARS = 5;

    private final PayrollService payrollService;
    private final ReportScope reportScope;

    // ---- provident fund -----------------------------------------------------

    /**
     * The PF ECR lines for a period, in the EPFO column order.
     *
     * <p>Only members with a PF deduction appear - a zero-contribution line
     * is not something EPFO's ECR accepts. {@code epfWages} is the payroll's
     * own {@code earnPf} (PF basic prorated by payable days), not a fresh
     * calculation, so the return can never state a different wage from the
     * one the employee's deduction was taken on.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.PfEcrRow> pfEcrReport(int month, int year, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);

        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> byUserId.containsKey(payroll.getEmployeeId()))
                .filter(payroll -> signum(payroll.getPfDeduction()) > 0)
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> {
                    Employee employee = byUserId.get(payroll.getEmployeeId());
                    BigDecimal epfWages = scaled(payroll.getEarnPf());
                    BigDecimal pensionableWages = epfWages.min(PENSION_WAGE_CEILING);
                    BigDecimal epsContribution = percentOf(pensionableWages, EPS_PERCENT);
                    BigDecimal employeeContribution = scaled(payroll.getPfDeduction());

                    return new ReportDtos.PfEcrRow(
                            employee == null ? null : employee.getUanNo(),
                            payroll.getEmployeeId(),
                            payroll.getEmployeeCode(),
                            payroll.getEmployeeName(),
                            scaled(payroll.getEarnGrossSalary()),
                            epfWages,
                            pensionableWages,
                            pensionableWages,
                            employeeContribution,
                            epsContribution,
                            employeeContribution.subtract(epsContribution).max(BigDecimal.ZERO),
                            days(payroll.getLopDays()),
                            BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
                })
                .toList();
    }

    // ---- employees' state insurance ----------------------------------------

    /**
     * The ESI monthly return lines for a period. Only insured persons - the
     * employees actually inside the wage ceiling, which is exactly those the
     * payroll deducted ESIC from.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.EsiReturnRow> esiReturnReport(int month, int year, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);

        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> byUserId.containsKey(payroll.getEmployeeId()))
                .filter(payroll -> signum(payroll.getEsic()) > 0)
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> {
                    Employee employee = byUserId.get(payroll.getEmployeeId());
                    BigDecimal wages = scaled(payroll.getEarnGrossSalary());
                    BigDecimal employeeShare = scaled(payroll.getEsic());
                    // The employer share is computed on the same base the
                    // employee share was taken on - earned basicDA, per
                    // DeductionCalculationService#calculateEsic - so the two
                    // sides of the return always agree on the wage.
                    BigDecimal employerShare = percentOf(scaled(payroll.getEarnBasicDA()), ESI_EMPLOYER_PERCENT);

                    return new ReportDtos.EsiReturnRow(
                            employee == null ? null : employee.getEsicIpNo(),
                            payroll.getEmployeeId(),
                            payroll.getEmployeeCode(),
                            payroll.getEmployeeName(),
                            days(payroll.getPayableDays()),
                            wages,
                            employeeShare,
                            employerShare,
                            employeeShare.add(employerShare));
                })
                .toList();
    }

    // ---- professional tax ---------------------------------------------------

    /** The professional tax register: who was taxed, on what, and how much. */
    @Transactional(readOnly = true)
    public List<ReportDtos.ProfessionalTaxRow> professionalTaxRegister(int month, int year, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);

        return payrollService.getPeriod(month, year).stream()
                .filter(payroll -> byUserId.containsKey(payroll.getEmployeeId()))
                .filter(payroll -> signum(payroll.getProfessionalTax()) > 0)
                .sorted(Comparator.comparing(Payroll::getEmployeeId))
                .map(payroll -> new ReportDtos.ProfessionalTaxRow(
                        payroll.getEmployeeId(),
                        payroll.getEmployeeCode(),
                        payroll.getEmployeeName(),
                        payroll.getDepartmentName() != null ? payroll.getDepartmentName()
                                : names.department(byUserId.get(payroll.getEmployeeId())),
                        scaled(payroll.getGrossSalary()),
                        scaled(payroll.getEarnGrossSalary()),
                        scaled(payroll.getProfessionalTax())))
                .toList();
    }

    // ---- TDS / Form 24Q -----------------------------------------------------

    /**
     * Quarterly TDS per employee for an Indian financial year (April to
     * March), with the month-wise split each quarter is made of.
     *
     * <p>Quarter 1 is April-June. {@code financialYear} is the year the
     * April falls in, so {@code financialYear=2026, quarter=4} is
     * January-March 2027.
     *
     * <p>Note for whoever files this: Form 24Q also needs each deductee's
     * PAN, which this schema does not hold anywhere - {@code Employee} has
     * UAN, ESIC IP and bank details, no PAN. The figures below are complete;
     * the PAN column has to come from elsewhere until the master carries one.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.Tds24qRow> tds24qReport(int financialYear, int quarter, ReportFilter filter) {
        List<Employee> employees = reportScope.employees(filter);
        Map<String, Employee> byUserId = reportScope.byUserId(employees);
        ReportScope.MasterNames names = reportScope.names(employees);

        List<YearMonth> months = quarterMonths(financialYear, quarter);
        Map<String, List<Payroll>> byEmployee = new LinkedHashMap<>();
        for (YearMonth period : months) {
            payrollService.getPeriod(period.getMonthValue(), period.getYear()).stream()
                    .filter(payroll -> byUserId.containsKey(payroll.getEmployeeId()))
                    .forEach(payroll -> byEmployee
                            .computeIfAbsent(payroll.getEmployeeId(), key -> new ArrayList<>())
                            .add(payroll));
        }

        String label = "Q%d %s-%s".formatted(quarter,
                months.get(0).getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                months.get(2).getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
        String fyLabel = "%d-%02d".formatted(financialYear, (financialYear + 1) % 100);

        return byEmployee.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<Payroll> runs = entry.getValue();
                    Payroll any = runs.get(0);
                    List<ReportDtos.MonthAmount> monthly = runs.stream()
                            .sorted(Comparator.comparing(Payroll::getYear).thenComparing(Payroll::getMonth))
                            .map(payroll -> new ReportDtos.MonthAmount(
                                    PayrollAuditService.periodLabel(
                                            YearMonth.of(payroll.getYear(), payroll.getMonth())),
                                    scaled(payroll.getTotalEarnings()),
                                    scaled(payroll.getTds())))
                            .toList();

                    return new ReportDtos.Tds24qRow(
                            entry.getKey(),
                            any.getEmployeeCode(),
                            any.getEmployeeName(),
                            any.getDepartmentName() != null ? any.getDepartmentName()
                                    : names.department(byUserId.get(entry.getKey())),
                            quarter,
                            label,
                            fyLabel,
                            sum(monthly.stream().map(ReportDtos.MonthAmount::earnings).toList()),
                            sum(monthly.stream().map(ReportDtos.MonthAmount::tds).toList()),
                            monthly);
                })
                .toList();
    }

    /** The three calendar months of an Indian financial-year quarter, April-March. */
    private List<YearMonth> quarterMonths(int financialYear, int quarter) {
        YearMonth first = YearMonth.of(financialYear, 4).plusMonths((long) (quarter - 1) * 3);
        return List.of(first, first.plusMonths(1), first.plusMonths(2));
    }

    // ---- gratuity -----------------------------------------------------------

    /**
     * Gratuity accrued per employee as at a date, under the Payment of
     * Gratuity Act's standard formula:
     *
     * <pre>
     *   last drawn Basic+DA x 15 / 26 x completed years of service
     * </pre>
     *
     * <p>Nothing in this system defines a gratuity policy, so the statutory
     * default is what this uses - see the class Javadoc. "Last drawn Basic+DA"
     * is the employee's current structure, and completed years run from
     * {@code joiningDate} to the as-at date (or {@code relievingDate}, when
     * the employee has already left). Below {@value #GRATUITY_ELIGIBLE_YEARS}
     * years nothing is payable, but the row is still listed with a zero
     * accrual so the liability schedule shows who is approaching eligibility.
     */
    @Transactional(readOnly = true)
    public List<ReportDtos.GratuityAccrualRow> gratuityAccrualReport(LocalDate asOf, ReportFilter filter) {
        List<Employee> employees = reportScope.activeEmployees(filter);
        ReportScope.MasterNames names = reportScope.names(employees);

        return employees.stream()
                .filter(employee -> employee.getJoiningDate() != null)
                .map(employee -> {
                    LocalDate until = employee.getRelievingDate() != null
                            && employee.getRelievingDate().isBefore(asOf)
                            ? employee.getRelievingDate() : asOf;
                    BigDecimal years = yearsOfService(employee.getJoiningDate(), until);
                    int completedYears = years.setScale(0, RoundingMode.DOWN).intValue();
                    boolean eligible = completedYears >= GRATUITY_ELIGIBLE_YEARS;
                    BigDecimal basicDA = scaled(employee.getBasicDA());

                    return new ReportDtos.GratuityAccrualRow(
                            employee.getUserId(),
                            employee.getEmployeeCode(),
                            employee.getEmployeeName(),
                            names.department(employee),
                            names.designation(employee),
                            employee.getJoiningDate(),
                            until,
                            years,
                            completedYears,
                            eligible,
                            basicDA,
                            gratuityAccrual(basicDA, completedYears));
                })
                .toList();
    }

    /**
     * The accrual itself, for one employee's figures - the single place the
     * Payment of Gratuity Act formula lives, so the liability schedule and a
     * leaver's full &amp; final worksheet can never quote different amounts
     * for the same person. Below {@link #GRATUITY_ELIGIBLE_YEARS} completed
     * years nothing is payable.
     */
    public BigDecimal gratuityAccrual(BigDecimal lastDrawnBasicDA, int completedYears) {
        if (completedYears < GRATUITY_ELIGIBLE_YEARS) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return scaled(lastDrawnBasicDA)
                .multiply(GRATUITY_DAYS_PER_YEAR)
                .divide(GRATUITY_MONTH_DAYS, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(completedYears))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Completed service in years, to one decimal - the same 365.25-day year a service certificate uses. */
    static BigDecimal yearsOfService(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return BigDecimal.ZERO.setScale(DAY_SCALE, RoundingMode.HALF_UP);
        }
        long days = ChronoUnit.DAYS.between(from, to);
        return BigDecimal.valueOf(days)
                .divide(new BigDecimal("365.25"), DAY_SCALE, RoundingMode.DOWN);
    }

    // ---- helpers ------------------------------------------------------------

    private BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        if (base == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return base.multiply(percent).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal days(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(DAY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream()
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private int signum(BigDecimal value) {
        return value == null ? 0 : value.signum();
    }
}
