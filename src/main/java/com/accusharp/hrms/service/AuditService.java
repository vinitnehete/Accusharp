package com.accusharp.hrms.service;

import com.accusharp.hrms.entity.AuditLog;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.repository.AuditLogRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.util.CsvSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Records one row per security-sensitive action. Always
 * {@code REQUIRES_NEW}: an audit record must commit independently of the
 * business transaction it describes, both because a {@code FAILURE} record
 * is written by definition inside an operation that is about to roll back
 * (same reasoning as {@code AuthService}'s failed-login counter - see its
 * Javadoc), and because an audit trail that a later, unrelated failure could
 * silently erase is not a trail at all.
 *
 * <p><b>Never pass a password, token, or secret in {@code detail}</b> - it is
 * stored and later returned verbatim by {@code GET /api/audit-logs}.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final TenantContext tenantContext;

    @org.springframework.beans.factory.annotation.Value("${app.audit.minimum-retention-days:365}")
    private long minimumRetentionDays;

    /** For events with no authenticated actor yet (login) or where the outcome overrides who "did" it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWithActor(String actor, PrincipalType actorType, Long companyId,
                                String action, String resourceType, String resourceId,
                                AuditOutcome outcome, String detail) {
        auditLogRepository.save(AuditLog.builder()
                .timestamp(Instant.now())
                .actor(actor)
                .actorType(actorType)
                .companyId(companyId)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .outcome(outcome)
                .detail(detail)
                .build());
    }

    /** For events performed by whoever is currently authenticated - resolves actor/company from SecurityContext. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId,
                       AuditOutcome outcome, String detail) {
        UserPrincipal principal = tenantContext.currentPrincipal().orElse(null);
        String actor = principal == null ? null : principal.getUsername();
        PrincipalType actorType = principal == null ? null : principal.getType();
        Long companyId = principal == null ? null : principal.getCompanyId();
        recordWithActor(actor, actorType, companyId, action, resourceType, resourceId, outcome, detail);
    }

    /**
     * Most recent entries first. A company-scoped caller (see
     * {@link TenantContext}) sees only their own company's trail - including
     * platform-attributed events like {@code COMPANY_ONBOARD} that recorded
     * that company's id, but never another company's. A platform caller
     * sees everything, since nothing platform-level is company-scoped.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return tenantContext.currentCompanyId()
                .map(companyId -> auditLogRepository.findAllByCompanyIdOrderByTimestampDesc(
                        companyId, PageRequest.of(0, bounded)))
                .orElseGet(() -> auditLogRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, bounded)));
    }

    /**
     * Every entry in the window, not capped at 200 like {@link #recent} -
     * for export, where silently truncating history would defeat the point.
     * Same company scoping as every other read here.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> exportRange(Instant from, Instant to) {
        return tenantContext.currentCompanyId()
                .map(companyId -> auditLogRepository
                        .findAllByCompanyIdAndTimestampBetweenOrderByTimestampDesc(companyId, from, to))
                .orElseGet(() -> auditLogRepository.findAllByTimestampBetweenOrderByTimestampDesc(from, to));
    }

    /**
     * Deletes every row older than {@code cutoff}, unscoped - gated by
     * {@code AUDIT_MANAGE}, which is deliberately platform-only (see {@code
     * PermissionCode}'s Javadoc): a company caller can read their own trail
     * but never erase it, even the parts of it that are about their own
     * actions. The purge itself is audited, in its own {@code
     * REQUIRES_NEW} transaction, same as everything else here - so deleting
     * old rows never erases the fact that a deletion happened.
     */
    /**
     * Permanently deletes audit history older than {@code cutoff}.
     *
     * <p>Two guards, because this is the one operation in the application that
     * destroys the record of every other operation:
     *
     * <ul>
     *   <li><b>A recency floor.</b> Refuses to purge anything inside the
     *       retention window ({@code app.audit.minimum-retention-days},
     *       default 365). Without it, a single mistyped date - or an actor who
     *       wants their own tracks gone - erases the trail that would show
     *       what just happened. Deleting yesterday's audit log is never
     *       routine maintenance.</li>
     *   <li><b>Export before delete.</b> The caller must pass the cutoff twice,
     *       once as {@code beforeDate} and once as {@code confirmExportedUpTo},
     *       to acknowledge the range has been exported via
     *       {@code GET /api/audit-logs/export}. A single-parameter irreversible
     *       delete is too easy to issue by accident.</li>
     * </ul>
     */
    @Transactional
    public long purgeOlderThan(Instant cutoff, Instant confirmExportedUpTo) {
        if (confirmExportedUpTo == null || !confirmExportedUpTo.equals(cutoff)) {
            throw new BusinessRuleException(
                    "Audit purge is irreversible. Export the range first via "
                            + "GET /api/audit-logs/export, then repeat the same date as "
                            + "confirmExportedUpTo to confirm.");
        }
        Instant floor = Instant.now().minus(Duration.ofDays(minimumRetentionDays));
        if (cutoff.isAfter(floor)) {
            throw new BusinessRuleException(
                    "Refusing to purge audit history newer than " + minimumRetentionDays
                            + " days (cutoff must be on or before " + floor + "). Raise "
                            + "app.audit.minimum-retention-days deliberately if this is really intended.");
        }
        long deleted = auditLogRepository.deleteByTimestampBefore(cutoff);
        record("AUDIT_LOG_PURGE", "AuditLog", null, AuditOutcome.SUCCESS,
                "cutoff=" + cutoff + " deleted=" + deleted);
        return deleted;
    }

    /** Spreadsheet-friendly export of {@link #exportRange}. */
    @Transactional(readOnly = true)
    public String renderCsv(Instant from, Instant to) {
        StringBuilder csv = new StringBuilder(2048);
        csv.append("timestamp,actor,actorType,companyId,action,resourceType,resourceId,outcome,detail\n");
        for (AuditLog entry : exportRange(from, to)) {
            csv.append(csvCell(entry.getTimestamp().toString())).append(',')
                    .append(csvCell(entry.getActor())).append(',')
                    .append(csvCell(entry.getActorType() == null ? null : entry.getActorType().name())).append(',')
                    .append(entry.getCompanyId() == null ? "" : entry.getCompanyId()).append(',')
                    .append(csvCell(entry.getAction())).append(',')
                    .append(csvCell(entry.getResourceType())).append(',')
                    .append(csvCell(entry.getResourceId())).append(',')
                    .append(csvCell(entry.getOutcome().name())).append(',')
                    .append(csvCell(entry.getDetail())).append('\n');
        }
        return csv.toString();
    }

    private String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = CsvSanitizer.neutralizeFormula(value).replace("\"", "\"\"");
        return cleaned.contains(",") ? "\"" + cleaned + "\"" : cleaned;
    }
}
