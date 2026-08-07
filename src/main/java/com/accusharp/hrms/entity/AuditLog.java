package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.PrincipalType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One security-sensitive action. Deliberately plain scalars, not JPA
 * relations - an audit record is a snapshot of what happened, not a live
 * view of current state (the actor or resource it describes may since have
 * been renamed or deleted), and a {@code @ManyToOne} here would reintroduce
 * the exact lazy-serialization footgun fixed on {@code SalaryRule}/{@code Holiday}
 * (see SECURITY.md).
 *
 * <p>Never put a password, token, or secret in {@code detail} - see
 * {@code AuditService}.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_company_time", columnList = "company_id,timestamp"),
        @Index(name = "idx_audit_log_actor_time", columnList = "actor,timestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    /** The username (Employee.userId or PlatformUser.username); null only when truly unknown (e.g. login with a nonexistent username). */
    @Column(length = 50)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", length = 20)
    private PrincipalType actorType;

    /** Null for platform-level events, or when the actor has no company. */
    @Column(name = "company_id")
    private Long companyId;

    /** e.g. LOGIN, LOGOUT, PASSWORD_CHANGE, EMPLOYEE_CREATE, PAYROLL_GENERATE. */
    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuditOutcome outcome;

    @Column(length = 300)
    private String detail;
}
