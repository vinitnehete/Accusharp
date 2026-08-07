package com.accusharp.hrms.service;

import com.accusharp.hrms.entity.AuditLog;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.repository.AuditLogRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
}
