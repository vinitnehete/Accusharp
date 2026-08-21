package com.accusharp.hrms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Configurable attendance-calculation thresholds, following the same
 * per-company + global-default pattern as {@link SalaryRule}: one row per
 * company, plus exactly one row with {@code company = null} - the global
 * default a company falls back to until it sets its own (see
 * {@link com.accusharp.hrms.service.AttendanceRuleService}).
 *
 * <p>Shift-level config (start/end time, grace period, break minutes,
 * overtime window) already lives on {@link Shift}, which is already
 * per-company - this entity only covers the handful of values that were
 * previously hardcoded identically for every company in {@code
 * AttendanceCalculationService}.
 */
@Entity
// Same "one row per company" database-level guard as SalaryRule - see its
// Javadoc for why this closes the concurrent-duplicate-insert race that a
// service-layer check-then-act alone cannot.
@Table(name = "attendance_rule", uniqueConstraints = @UniqueConstraint(columnNames = "company_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means the global default every company falls back to. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    /**
     * How many minutes before shift start a punch is still accepted into
     * that shift's window. Not symmetric on the close side - the window's
     * close is bounded by the shift's own {@code overtimeWindowMinutes}
     * instead, since someone who stays late has worked overtime and must
     * not be read as having never punched out.
     */
    @Column(name = "entry_window_buffer_minutes", nullable = false)
    private int entryWindowBufferMinutes;

    /** Worked share of the shift, as a percent, needed to count as a full day. */
    @Column(name = "full_day_threshold_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal fullDayThresholdPercent;

    /** Worked share of the shift, as a percent, needed to count as a half day. */
    @Column(name = "half_day_threshold_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal halfDayThresholdPercent;

    /** Sane starting values for a new rule row - the global default, or a fresh per-company one. Matches the values every company used before this was configurable. */
    public static AttendanceRule defaultRule() {
        return AttendanceRule.builder()
                .entryWindowBufferMinutes(60)
                .fullDayThresholdPercent(new BigDecimal("75.00"))
                .halfDayThresholdPercent(new BigDecimal("40.00"))
                .build();
    }
}
