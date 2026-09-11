package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One employee's attendance for one day, as a stored record rather than a
 * derived view.
 *
 * <p>This is the reviewed artifact payroll is paid from. Generation writes it
 * from raw punches; an admin may then correct a day, which flips
 * {@code recordStatus} to {@link AttendanceRecordStatus#MANUAL} and protects it
 * from being recomputed away on the next generation run.
 *
 * <p>The row is deliberately self-contained: {@code weekOff}, {@code holiday}
 * and {@code shiftCode} are snapshotted rather than re-read from the roster, so
 * a later roster or holiday-calendar edit can never silently restate a month
 * that has already been paid.
 */
@Entity
@Table(name = "emp_daily_attendance",
        uniqueConstraints = @UniqueConstraint(name = "uk_daily_attendance_user_date",
                columnNames = {"user_id", "attendance_date"}),
        indexes = @Index(name = "idx_daily_attendance_date", columnList = "attendance_date"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    /** Snapshot of the shift the day was interpreted through. */
    @Column(name = "shift_code", length = 30)
    private String shiftCode;

    @Column(name = "first_in")
    private LocalDateTime firstIn;

    @Column(name = "last_out")
    private LocalDateTime lastOut;

    @Column(name = "working_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal workingHours;

    @Column(name = "break_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal breakHours;

    @Column(name = "overtime_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal overtimeHours;

    @Column(name = "late_minutes", nullable = false)
    private int lateMinutes;

    @Column(name = "early_exit_minutes", nullable = false)
    private int earlyExitMinutes;

    @Column(name = "invalid_punch", nullable = false)
    private boolean invalidPunch;

    /** Snapshotted so the working-day count cannot drift after payment. */
    @Column(name = "week_off", nullable = false)
    private boolean weekOff;

    @Column(name = "holiday", nullable = false)
    private boolean holiday;

    /**
     * Whether this date was one of the employee's configured weekly-off days
     * when the day was generated - see {@code Employee#hasConfiguredWeekOffOn}.
     *
     * <p>Not the same as {@link #weekOff}. HR can roster someone onto their
     * weekly off, which makes it a working day ({@code weekOff = false}) that is
     * still their weekly off - and that is exactly the day "who worked their
     * weekly off" has to find.
     *
     * <p>Snapshotted, like {@link #holiday}, rather than re-derived from the
     * employee record. Every report resyncs the monthly summaries from these
     * rows before reading them, so a re-derived flag would let a change to
     * someone's weekly off in October silently rewrite September.
     */
    @Column(name = "configured_week_off", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean configuredWeekOff;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_status", nullable = false, length = 20)
    private AttendanceRecordStatus recordStatus;

    /**
     * Frozen because payroll has been generated for the period. Named
     * {@code is_locked} because {@code LOCKED} is a reserved word on MySQL 8.
     */
    @Column(name = "is_locked", nullable = false)
    private boolean locked;

    /** Why the day was corrected - required on every manual edit. */
    @Column(length = 500)
    private String remarks;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "generated_by", length = 50)
    private String generatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    /**
     * Guards the concurrent-generation race the unique constraint above can only
     * turn into a constraint violation. Two generation runs for the same
     * employee and month both read the existing rows, both recompute, and both
     * write; without a version the later write silently wins with figures
     * derived from a snapshot taken before the earlier one landed.
     *
     * <p>The column is declared {@code not null default 0} rather than plain
     * nullable on purpose: adding a nullable version column to a table that
     * already has rows leaves every one of them at {@code NULL}, and Hibernate
     * then puts {@code row_version = NULL} in the update predicate, which
     * matches nothing - every existing day would become unwritable. The default
     * makes the {@code ALTER} backfill existing rows with 0 instead. On any
     * database where that does not hold, backfill before deploying:
     * {@code UPDATE emp_daily_attendance SET row_version = 0 WHERE row_version IS NULL;}
     */
    @Version
    @Column(name = "row_version", nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    /** A day the employee was expected to work - the LOP denominator. */
    public boolean isWorkingDay() {
        return !weekOff && !holiday;
    }

    /** A weekly off - by roster or by the employee's own configuration - that was worked. */
    public boolean isWorkedOnWeekOff() {
        return (weekOff || configuredWeekOff) && status.dayFraction().signum() > 0;
    }

    /**
     * Punches on a weekly off with no shift assigned. Not worked, by the rule HR
     * set - with no shift the day is not present - but it is either a roster
     * somebody forgot to write or a day worked unpaid, and HR needs to see both.
     */
    public boolean isUnrosteredPunchOnWeekOff() {
        return (weekOff || configuredWeekOff) && shiftCode == null && firstIn != null
                && status.dayFraction().signum() == 0;
    }
}
