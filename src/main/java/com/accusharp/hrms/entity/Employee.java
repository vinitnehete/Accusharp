package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.Gender;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * Employee master. Holds the salary <em>structure</em>, never a specific
 * month's pay - that lives in {@link Payroll}.
 *
 * <p>{@code userId} is the business key used across attendance, leave and
 * payroll; {@code id} never leaves this table.
 */
@Entity
@Table(name = "employee",
        uniqueConstraints = {
                // Global, not per-company, deliberately: login (AuthService#dispatchLogin)
                // and the biometric device feed both resolve an employee by userId alone,
                // with no company selector in either flow - two companies sharing a userId
                // would make login ambiguous. See docs/migrations/2026-08-08-per-company-masters.sql
                // for the equivalent per-company move made for Department/Designation/Shift,
                // which don't have that constraint.
                @UniqueConstraint(name = "uk_employee_user_id", columnNames = "user_id"),
                // Per-company (unlike userId above): employeeCode is a display/reporting
                // code, never looked up across companies, so two companies numbering their
                // own staff "EMP001" independently is the expected case, not a collision.
                @UniqueConstraint(name = "uk_employee_company_code", columnNames = {"company_id", "employee_code"})
        },
        indexes = {
                @Index(name = "idx_employee_supervisor", columnList = "supervisor_id"),
                // company_id/department_id/designation_id/category_id are all hit by
                // frequently-used repository finders (findByCompanyId, findByDepartmentId,
                // findByCategoryId, and the delete-guard checks in DepartmentService/
                // CategoryService) - unindexed, these degrade to full table scans as
                // headcount grows. Flagged in the audit's database review.
                @Index(name = "idx_employee_company", columnList = "company_id"),
                @Index(name = "idx_employee_department", columnList = "department_id"),
                @Index(name = "idx_employee_designation", columnList = "designation_id"),
                @Index(name = "idx_employee_category", columnList = "category_id"),
                // Every contractor-scoped read (the workforce list, the roster
                // picker, attendance generation, the monthly report) filters on
                // this column, and every company-scoped read filters on it being
                // null - see EmployeeService#getActiveEntities.
                @Index(name = "idx_employee_contractor", columnList = "contractor_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key - also the biometric device user id. */
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    /** Optional - null when the company does not number its staff. Unique per company when given. */
    @Column(name = "employee_code", length = 50)
    private String employeeCode;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "designation_id")
    private Designation designation;

    /** Employee grade/category (Worker, Supervisor, Manager, Director, ...) - optional, company-defined. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** Self-reference: one employee reports to at most one supervisor. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervisor_id")
    private Employee supervisor;

    /**
     * Set only on a labour contractor's own worker; null on every employee of
     * the company itself. This one column is what separates the two
     * populations everywhere in the app.
     *
     * <p><b>Why these live in {@code employee} at all.</b> {@code ShiftSchedule},
     * {@code DailyAttendance}, {@code DeviceLog} and
     * {@code MonthlyAttendanceSummary} are keyed by the plain {@code userId}
     * string, and {@link #userId} is unique platform-wide because the biometric
     * device feed resolves a punch by it alone. A parallel worker table would
     * either collide with that key or need a second copy of the whole
     * attendance engine - so a contractor's worker is an employee row that the
     * company-scoped queries exclude, not a different kind of record.
     *
     * <p><b>What it excludes them from.</b>
     * {@code EmployeeService#getActiveEntities()} and
     * {@code getAllEntities()} - the two choke points payroll, the dashboard,
     * every report and the employee directory read - filter these out, so a
     * contractor's worker can never appear in a payroll run, a PF/ESIC return
     * or the company headcount. They are reached only through the
     * contractor-scoped finders beside those.
     *
     * <p>The salary structure, the statutory identifiers, the bank details and
     * the login credentials on this entity all stay null for them: the client
     * company rosters these people and reports their attendance, it does not
     * pay them.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contractor_id")
    private Contractor contractor;

    /** True when this row is a labour contractor's worker rather than the company's own employee. */
    public boolean isContractorWorker() {
        return contractor != null;
    }

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    /**
     * Last working day, set when the employee is deactivated. Null while
     * active. Payroll uses this (and {@link #joiningDate}) to bound how many
     * days of a period this employee was actually employed for - see
     * {@code PayrollService#employedDaysInPeriod}.
     */
    @Column(name = "relieving_date")
    private LocalDate relievingDate;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Optional. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 20)
    private EmployeeStatus status;

    /**
     * The configurable employment type, and the payroll behaviour that comes
     * with it - see {@link EmploymentType}.
     *
     * <p><b>Nullable, and {@link #status} above stays.</b> The two coexist on
     * purpose: null here means payroll falls back to the legacy
     * {@link EmployeeStatus} semantics, which is exactly what every existing
     * row does and exactly what it did before this column existed. Adopting
     * configurable types is therefore opt-in per employee rather than a
     * migration a company must finish before its next payroll run, and rolling
     * back is clearing one column.
     *
     * <p>{@code status} remains the field the CSV importer, the roster default
     * and every report read, so nothing outside {@code PayBehaviourResolver}
     * has to know this column exists yet.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employment_type_id")
    private EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_status", nullable = false, length = 20)
    private RecordStatus recordStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    private String email;

    @Column(length = 20)
    private String phone;

    // ---- statutory & bank details - all optional -----------------------
    // Encrypted at rest (AES-256-GCM) - see EncryptedStringConverter. These
    // four are the only employee fields worth anything to someone who gets a
    // copy of the database, and none of them is ever queried or filtered
    // (only set and mapped onto a response), which is what makes encrypting
    // them free of functional consequence.
    //
    // The lengths below are CIPHERTEXT widths, not value widths: GCM plus
    // base64 expands a 30-character account number to roughly 90.

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "uan_no", length = 255)
    private String uanNo;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "esic_ip_no", length = 255)
    private String esicIpNo;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "bank_account_no", length = 255)
    private String bankAccountNo;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "bank_ifsc_no", length = 255)
    private String bankIfscNo;

    // ---- salary structure -------------------------------------------------
    // grossSalary, pfBasic, medicalAllowance and otherAllowance are entered;
    // the rest are derived from SalaryRule on every create/update.

    @Column(name = "gross_salary", precision = 15, scale = 2)
    private BigDecimal grossSalary;

    @Column(name = "pf_basic", precision = 15, scale = 2)
    private BigDecimal pfBasic;

    @Column(name = "basic_da", precision = 15, scale = 2)
    private BigDecimal basicDA;

    @Column(precision = 15, scale = 2)
    private BigDecimal hra;

    @Column(name = "conveyance_allowance", precision = 15, scale = 2)
    private BigDecimal conveyanceAllowance;

    @Column(name = "education_allowance", precision = 15, scale = 2)
    private BigDecimal educationAllowance;

    @Column(name = "medical_allowance", precision = 15, scale = 2)
    private BigDecimal medicalAllowance;

    @Column(name = "other_allowance", precision = 15, scale = 2)
    private BigDecimal otherAllowance;

    /** Sum of every earning component above. */
    @Column(name = "gross_salary_wage", precision = 15, scale = 2)
    private BigDecimal grossSalaryWage;

    /**
     * When true, basicDA/hra/conveyanceAllowance/educationAllowance were set
     * by hand (see {@code EmployeeService#updateSalaryStructure}) and are no
     * longer derived from {@link SalaryRule} on save - until
     * {@code EmployeeService#regenerateSalaryStructure} clears it.
     */
    @Column(name = "salary_structure_overridden", nullable = false)
    @Builder.Default
    private boolean salaryStructureOverridden = false;

    @Column(name = "overtime_eligible", nullable = false)
    private boolean overtimeEligible;

    // ---- the working week ---------------------------------------------------

    /**
     * Which days of the week this employee does not work.
     *
     * <p>Null means unconfigured, and is <b>not</b> the same as empty - see
     * {@link #effectiveWeekOffDays()} and {@link WeekOffDaysConverter}.
     *
     * <p>This is the whole of what a roster row used to say about an employee
     * who always works the same shift. They were previously given ninety
     * generated {@code ShiftSchedule} rows a quarter whose only content was
     * "Sunday is off" - hardcoded, so every employee got Sunday whether that
     * was their day or not, and the rows ran out two months ahead and took
     * their attendance with them.
     */
    @Convert(converter = WeekOffDaysConverter.class)
    @Column(name = "week_off_days", length = 120)
    private Set<DayOfWeek> weekOffDays;

    /**
     * The week-off days to actually apply, resolving the unconfigured case.
     *
     * <p>Null resolves to Sunday: that is what the deleted
     * {@code DefaultRosterService} wrote into every roster row it created, so
     * an existing database - where this column is null on every row - keeps
     * behaving exactly as it did. Adopting the field is then per-employee, and
     * setting it to an empty set is how a company says an employee has no
     * weekly off at all.
     */
    public Set<DayOfWeek> effectiveWeekOffDays() {
        return weekOffDays == null ? Set.of(DayOfWeek.SUNDAY) : weekOffDays;
    }

    /** Whether this date falls on one of this employee's week-off days. */
    public boolean isWeekOffOn(LocalDate date) {
        return effectiveWeekOffDays().contains(date.getDayOfWeek());
    }

    /**
     * Whether this date is one of this employee's weekly-off days, for the two
     * places that ask regardless of how the day was rostered: turning an
     * unassigned day into a weekly off rather than an absence, and tracking who
     * worked theirs.
     *
     * <p>Auto-rostered employees keep {@link #isWeekOffOn}'s unset-means-Sunday
     * fallback, because that is what their roster rows always said. Everyone
     * else has a weekly off only once somebody sets one: the deleted cron never
     * rostered day-wise or contract staff, so for them there is no existing
     * behaviour to preserve - and every reason not to invent a day off nobody
     * agreed to.
     */
    public boolean hasConfiguredWeekOffOn(LocalDate date) {
        if (autoRostersDefaultShift()) {
            return isWeekOffOn(date);
        }
        return weekOffDays != null && weekOffDays.contains(date.getDayOfWeek());
    }

    /**
     * Whether attendance generation should put this employee on the default
     * {@code GENERAL} shift for days nobody rostered explicitly.
     *
     * <p>Reads {@link EmploymentType#isAutoRosterDefaultShift()} when the
     * employee has a configurable type, and otherwise falls back to the legacy
     * {@code status == PERMANENT} rule - the same fallback shape
     * {@code PayBehaviourResolver} uses, and for the same reason: a database
     * with no {@code employment_type} rows behaves precisely as it did before
     * the table existed.
     *
     * <p>Deliberately not routed through {@code PayBehaviourResolver}, which
     * needs a {@code SalaryRule} the attendance engine has no business loading.
     *
     * <p>A contractor's worker never qualifies, whatever the flag says. They
     * are on site only for the days their contractor sends them, so a default
     * roster would manufacture absent days - and therefore an invoice dispute -
     * for days nobody was expected.
     */
    public boolean autoRostersDefaultShift() {
        if (isContractorWorker()) {
            return false;
        }
        return employmentType == null
                ? status == EmployeeStatus.PERMANENT
                : employmentType.isAutoRosterDefaultShift();
    }

    // ---- authentication -----------------------------------------------------
    // userId doubles as the login username. Never serialized to any response DTO.

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "account_enabled", nullable = false)
    @Builder.Default
    private boolean accountEnabled = true;

    @Column(name = "account_locked", nullable = false)
    @Builder.Default
    private boolean accountLocked = false;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /**
     * Set whenever HR hands this employee a system-generated temporary
     * password (create, reset-password) - never by anything else, so
     * existing accounts are unaffected. Cleared once the employee actually
     * sets their own password via {@code POST /api/auth/change-password}.
     * Surfaced on {@code TokenResponse} so the frontend can force the
     * change-password screen before letting a temporary password stay live
     * indefinitely - the gap the audit's authentication review flagged.
     */
    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;
}
