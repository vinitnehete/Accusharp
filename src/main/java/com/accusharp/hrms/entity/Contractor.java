package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.RecordStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * A labour contractor engaged by one company - the agency whose own workers
 * are deployed on this company's site.
 *
 * <p>Unlike {@code Department}/{@code Designation}/{@code Shift} there is no
 * shared ({@code company = null}) catalog here: a contractor is a commercial
 * relationship between one company and one agency, never a reference row
 * other companies could meaningfully read. {@code company} is therefore
 * non-null, and one company routinely engages several.
 *
 * <p>What the contractor's workers are <em>paid</em> is deliberately absent
 * from this model. The client company rosters them, generates their
 * attendance and sends the contractor a report; the contractor runs their
 * payroll from it. Nothing here - and nothing on the {@link Employee} rows
 * that point at it - carries a salary structure, a statutory identifier or a
 * login.
 */
@Entity
@Table(name = "contractor",
        uniqueConstraints = @UniqueConstraint(name = "uk_contractor_company_code",
                columnNames = {"company_id", "contractor_code"}),
        indexes = @Index(name = "idx_contractor_company", columnList = "company_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contractor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The engaging company. Never null - see class Javadoc for why this has no
     * shared-catalog variant. {@code @JsonIgnore} for the same reason
     * {@link Department} ignores its own: a lazy proxy must never reach the
     * wire, and the mapper flattens what callers actually need.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** The company's own reference for this contractor - unique within it, not globally. */
    @Column(name = "contractor_code", nullable = false, length = 30)
    private String contractorCode;

    @Column(name = "contractor_name", nullable = false)
    private String contractorName;

    /** Who the attendance report is actually sent to. */
    @Column(name = "contact_person")
    private String contactPerson;

    private String email;

    @Column(length = 20)
    private String phone;

    @Column(length = 500)
    private String address;

    @Column(name = "gst_no", length = 20)
    private String gstNo;

    @Column(name = "pan_no", length = 20)
    private String panNo;

    /** The engagement window. Advisory - it bounds nothing the attendance engine does. */
    @Column(name = "agreement_start_date")
    private LocalDate agreementStartDate;

    @Column(name = "agreement_end_date")
    private LocalDate agreementEndDate;

    @Column(length = 500)
    private String notes;

    /**
     * Deactivated rather than deleted once workers exist under it, the same
     * rule {@link Employee} follows: a past month's attendance report must
     * keep resolving the contractor it was sent to.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "record_status", nullable = false, length = 20)
    @Builder.Default
    private RecordStatus recordStatus = RecordStatus.ACTIVE;
}
