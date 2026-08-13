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

    /** A day the employee was expected to work - the LOP denominator. */
    public boolean isWorkingDay() {
        return !weekOff && !holiday;
    }
}
