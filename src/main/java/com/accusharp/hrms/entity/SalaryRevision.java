package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.SalaryRevisionReason;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per salary hike/promotion/correction - the append-only audit trail
 * {@link SalaryRule}'s own Javadoc flags as missing. {@link Employee#getGrossSalary()}
 * always holds the current value; this table only records how it got there.
 *
 * <p>Plain scalar {@code employeeId}, not a JPA relation - same reasoning as
 * {@code AuditLog}: a history record is a snapshot of what happened, not a
 * live view of current employee state.
 */
@Entity
@Table(name = "salary_revision",
        indexes = @Index(name = "idx_salary_revision_employee", columnList = "employee_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Employee.userId business key. */
    @Column(name = "employee_id", nullable = false, length = 50)
    private String employeeId;

    @Column(name = "previous_gross_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal previousGrossSalary;

    @Column(name = "new_gross_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal newGrossSalary;

    /** (newGrossSalary - previousGrossSalary) / previousGrossSalary x 100, signed - negative for a pay cut. */
    @Column(name = "hike_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal hikePercent;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SalaryRevisionReason reason;

    @Column(length = 300)
    private String remarks;

    /** Employee.userId or PlatformUser.username of whoever applied this - same actor convention as AuditLog. */
    @Column(name = "revised_by", length = 50)
    private String revisedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
