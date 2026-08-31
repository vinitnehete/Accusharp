package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.SalaryStructureChangeType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per salary-structure change - the append-only history for the
 * components, matching what {@link SalaryRevision} already does for gross
 * salary.
 *
 * <p>It exists because an override used to leave no trace of what it replaced.
 * {@code EmployeeService#updateSalaryStructure} recorded an audit entry saying
 * <em>that</em> a structure had been overridden, with no values in it at all,
 * and the previous components were simply overwritten on the employee row and
 * gone. A single-employee edit made that survivable; a bulk upload across a
 * whole company would not have been, since nothing short of a database backup
 * could say what the figures had been.
 *
 * <p>Both sides of every field are stored, so a row reads on its own without
 * having to replay the whole history to work out what changed. {@code
 * grossSalaryWage} is deliberately absent: it is the sum of the six allowances
 * and is reconstructible from them.
 *
 * <p>Plain scalar {@code employeeId}, not a JPA relation - same reasoning as
 * {@link SalaryRevision} and {@code AuditLog}: a history record is a snapshot
 * of what happened, not a live view of current employee state.
 */
@Entity
@Table(name = "salary_structure_revision",
        indexes = @Index(name = "idx_salary_structure_revision_employee", columnList = "employee_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryStructureRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Employee.userId business key. */
    @Column(name = "employee_id", nullable = false, length = 50)
    private String employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private SalaryStructureChangeType changeType;

    // ---- before ------------------------------------------------------------

    @Column(name = "previous_basic_da", precision = 15, scale = 2)
    private BigDecimal previousBasicDA;

    @Column(name = "previous_hra", precision = 15, scale = 2)
    private BigDecimal previousHra;

    @Column(name = "previous_conveyance_allowance", precision = 15, scale = 2)
    private BigDecimal previousConveyanceAllowance;

    @Column(name = "previous_education_allowance", precision = 15, scale = 2)
    private BigDecimal previousEducationAllowance;

    @Column(name = "previous_medical_allowance", precision = 15, scale = 2)
    private BigDecimal previousMedicalAllowance;

    @Column(name = "previous_other_allowance", precision = 15, scale = 2)
    private BigDecimal previousOtherAllowance;

    @Column(name = "previous_gross_salary", precision = 15, scale = 2)
    private BigDecimal previousGrossSalary;

    /** Whether the structure was already frozen before this change. */
    @Column(name = "previously_overridden", nullable = false)
    private boolean previouslyOverridden;

    // ---- after -------------------------------------------------------------

    @Column(name = "new_basic_da", precision = 15, scale = 2)
    private BigDecimal newBasicDA;

    @Column(name = "new_hra", precision = 15, scale = 2)
    private BigDecimal newHra;

    @Column(name = "new_conveyance_allowance", precision = 15, scale = 2)
    private BigDecimal newConveyanceAllowance;

    @Column(name = "new_education_allowance", precision = 15, scale = 2)
    private BigDecimal newEducationAllowance;

    @Column(name = "new_medical_allowance", precision = 15, scale = 2)
    private BigDecimal newMedicalAllowance;

    @Column(name = "new_other_allowance", precision = 15, scale = 2)
    private BigDecimal newOtherAllowance;

    @Column(name = "new_gross_salary", precision = 15, scale = 2)
    private BigDecimal newGrossSalary;

    /** Whether the structure is frozen after this change. */
    @Column(name = "now_overridden", nullable = false)
    private boolean nowOverridden;

    // ---- provenance --------------------------------------------------------

    /** Always the authenticated caller, never client-supplied. */
    @Column(name = "revised_by", length = 50)
    private String revisedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
