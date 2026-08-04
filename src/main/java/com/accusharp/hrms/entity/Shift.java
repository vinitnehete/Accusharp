package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Shift master. The four standard shifts are seeded; admins may add custom
 * ones. A shift whose end time is not after its start time crosses midnight.
 */
@Entity
@Table(name = "shift", uniqueConstraints = @UniqueConstraint(columnNames = "shift_code"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shift_code", nullable = false, length = 30)
    private String shiftCode;

    @Column(name = "shift_name", nullable = false, length = 60)
    private String shiftName;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** Paid hours in the shift; overtime is measured against this. */
    @Column(name = "working_hours", nullable = false)
    private int workingHours;

    /** Unpaid break minutes deducted from the punch-to-punch span. */
    @Column(name = "break_minutes", nullable = false)
    private int breakMinutes;

    /** Minutes of tolerance before an entry counts as late. */
    @Column(name = "grace_minutes", nullable = false)
    private int graceMinutes;

    /**
     * How long after the scheduled end a punch still counts towards this shift.
     * This is what makes overtime visible: an exit punched three hours late is
     * overtime, not a missing punch. Keep it shorter than the gap to the next
     * shift so one day's exit is never read as the next day's entry.
     */
    @Column(name = "overtime_window_minutes", nullable = false)
    private int overtimeWindowMinutes;

    /** True when endTime falls on the next calendar day (e.g. 18:00 - 08:00). */
    public boolean crossesMidnight() {
        return !endTime.isAfter(startTime);
    }

    /** Wall-clock span of the shift, midnight crossover included. */
    public Duration span() {
        Duration duration = Duration.between(startTime, endTime);
        return duration.isNegative() || duration.isZero() ? duration.plusDays(1) : duration;
    }
}
