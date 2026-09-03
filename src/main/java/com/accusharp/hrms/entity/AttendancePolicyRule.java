package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One version of one attendance policy rule, for one population.
 *
 * <p>A company's policy is a stack of these, resolved per employee per date by
 * {@code AttendancePolicyResolver}: at most one rule of each {@link RuleType}
 * applies to an employee on a day, chosen by {@link RuleScope}'s precedence
 * chain. See {@code docs/design/attendance-policy-engine.md}.
 *
 * <h2>Append-only, and versions succeed rather than overlap</h2>
 *
 * <p>A rule is never edited. Changing policy appends a version with a later
 * {@code effectiveFrom}; ending one appends a version with {@code enabled =
 * false}. A version is in force from its own {@code effectiveFrom} until the
 * next version's, and the latest runs open-ended. This is the same shape
 * {@link SalaryRevision} and {@link SalaryStructureRevision} already use for
 * pay, for the same reason: the question asked six months later is <em>what was
 * the rule then</em>.
 *
 * <p>There is deliberately <b>no {@code effectiveTo}</b>. A from/to model
 * cannot have its central invariant - two contradictory rules must never both
 * apply to one day - enforced by any index MySQL 8 has; non-overlap of date
 * ranges needs PostgreSQL's {@code EXCLUDE}. That would leave the one
 * constraint that actually protects salaries resting on a service-layer
 * check-then-act, which {@link SalaryRule}'s Javadoc already records as
 * insufficient against concurrent inserts. With succession, overlap is
 * <em>structurally impossible</em> and {@code uk_policy_rule_effective} below
 * stops being merely necessary and becomes sufficient.
 *
 * <h2>Resolution reads the attendance date, never {@code now()}</h2>
 *
 * <p>A rule added in September cannot re-price August: replay resolves the row
 * that was in force on each attendance date and produces the same numbers it
 * produced before. That is what makes regeneration safe to run any number of
 * times, and it is why the only edit that can move an already-paid month is a
 * <em>back-dated</em> one - refused outright by {@code AttendancePolicyService}
 * when it reaches into a locked period.
 */
@Entity
@Table(name = "attendance_policy_rule",
        uniqueConstraints = {
                // At most one version of one rule type, for one population, starting
                // on one date. With succession (no effectiveTo) this is what makes
                // "two rules of the same type may not both apply" a database
                // guarantee rather than a service-layer hope.
                @UniqueConstraint(name = "uk_policy_rule_effective",
                        columnNames = {"company_id", "scope", "scope_ref", "rule_type", "effective_from"}),
                @UniqueConstraint(name = "uk_policy_rule_version",
                        columnNames = {"company_id", "scope", "scope_ref", "rule_type", "version"})
        },
        indexes = @Index(name = "idx_policy_rule_lookup",
                columnList = "company_id, rule_type, scope, scope_ref, effective_from"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendancePolicyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Null means a {@link RuleScope#GLOBAL} row - the shared fallback, writable
     * only by a platform caller, exactly as {@link Shift}'s shared catalog is.
     * Ships empty and is seeded with nothing: a seeded global rule would change
     * what every existing tenant is paid on the deploy that introduced it.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleScope scope;

    /**
     * What the scope names: a {@code userId}, a category/department/designation
     * <em>code</em>, an {@code EmployeeStatus} name, or {@link RuleScope#ANY}
     * for COMPANY and GLOBAL.
     *
     * <p>Codes rather than ids for the three masters: they have been unique per
     * company since the Phase 6 migration, and they read correctly in an
     * explanation - {@code CATEGORY=STAFF} is what an employee disputing a
     * deduction needs to see, where {@code CATEGORY=47} is not.
     *
     * <p>Not null, and that matters: both MySQL and H2 treat nulls in a unique
     * index as distinct, so a nullable column here would quietly let two
     * company-scoped rules of the same type and date coexist.
     */
    @Column(name = "scope_ref", nullable = false, length = 50)
    private String scopeRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 40)
    private RuleType ruleType;

    /** 1, 2, 3... within one (company, scope, scopeRef, ruleType) chain. Renders as "v3" in an explanation. */
    @Column(nullable = false)
    private int version;

    /** In force from this date until the next version's {@code effectiveFrom}; the latest runs open-ended. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /**
     * False means <b>this rule type does not apply to this population</b> -
     * which is today's behaviour for that type, not an absence of policy.
     * Resolution still picks this row (most specific wins), finds it disabled,
     * and applies nothing; a broader scope does <em>not</em> then take over.
     *
     * <p>This is the only opt-out mechanism, deliberately. "Managers are not
     * tracked for lateness" is a disabled {@code LATE_ARRIVAL} at
     * {@code CATEGORY=MANAGER}, not a second parameter meaning the same thing a
     * different way.
     */
    @Column(nullable = false)
    private boolean enabled;

    /**
     * The rule's typed parameters as JSON, bound to the per-type record {@code
     * AttendancePolicyParams} declares and bean-validated on write.
     *
     * <p>Stored as {@code text} rather than a native {@code json} column: this
     * app runs on MySQL in production and H2 in the test suite, and the two do
     * not agree on the JSON type. The column is a transport; the record is the
     * contract. It is never read as a loose map, and a blob that will not bind
     * fails generation for that employee by name rather than being skipped -
     * silently dropping a rule would change pay by omission, which is the one
     * outcome that must never happen quietly.
     */
    @Column(nullable = false, columnDefinition = "text")
    private String params;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** {@code Employee.userId} or {@code PlatformUser.username} - the same actor convention as {@code AuditLog}. */
    @Column(name = "created_by", length = 50)
    private String createdBy;

    /** Why this version exists. Free text, for the humans reading the chain later. */
    @Column(length = 500)
    private String notes;

    /** A version that has not started yet has priced nothing, and is the only kind that may be deleted. */
    public boolean isFuture(LocalDate asOf) {
        return effectiveFrom.isAfter(asOf);
    }

    /** Renders as the trace's scope label - {@code CATEGORY=STAFF}, {@code COMPANY}. */
    public String scopeLabel() {
        return scope.requiresRef() ? scope.name() + "=" + scopeRef : scope.name();
    }

    /** Renders as the trace's rule label - {@code LATE_ARRIVAL v3 scoped CATEGORY=STAFF}. */
    public String ruleLabel() {
        return ruleType.name() + " v" + version + " scoped " + scopeLabel();
    }
}
