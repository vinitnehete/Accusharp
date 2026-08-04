package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * One employee's shift for one date. The unique constraint is the rule
 * "an employee can only have one shift per day".
 */
@Entity
@Table(name = "emp_attendance_shift",
        uniqueConstraints = @UniqueConstraint(name = "uk_shift_schedule_user_date",
                columnNames = {"user_id", "shift_date"}),
        indexes = @Index(name = "idx_shift_schedule_date", columnList = "shift_date"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    /** Weekly off / holiday days are scheduled but not expected to be worked. */
    @Column(name = "week_off", nullable = false)
    private boolean weekOff;

    @Column(name = "assigned_by", length = 50)
    private String assignedBy;
}
