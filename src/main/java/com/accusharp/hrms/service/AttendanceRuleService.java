package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.AttendanceRuleRequest;
import com.accusharp.hrms.entity.AttendanceRule;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attendance-calculation thresholds, per company rather than hardcoded
 * identically for every tenant - see {@link AttendanceRule}'s Javadoc, and
 * {@link SalaryRuleService} for the identical pattern this mirrors.
 *
 * <p>A company without its own rule transparently falls back to the global
 * default (seeded with the values every company used before this class
 * existed) - so an existing company that has never customized its thresholds
 * behaves exactly as it did before this class existed. Only {@link
 * #updateRule} ever creates a company-specific row, and only for the
 * caller's own company.
 */
@Service
@RequiredArgsConstructor
public class AttendanceRuleService {

    private final AttendanceRuleRepository attendanceRuleRepository;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    /** The caller's own company's rule if one exists, else the global default. */
    @Transactional
    public AttendanceRule getActiveRule() {
        return tenantContext.currentCompanyId()
                .map(this::ruleForCompanyOrDefault)
                .orElseGet(this::globalDefault);
    }

    /**
     * Same resolution as {@link #getActiveRule}, but for an arbitrary
     * (possibly null) company id rather than the caller's own - the shape
     * every other per-company lookup in the attendance package already
     * uses (see {@code HolidayService.mandatoryHolidayDates}).
     */
    @Transactional
    public AttendanceRule getActiveRuleForCompany(Long companyId) {
        return companyId == null ? globalDefault() : ruleForCompanyOrDefault(companyId);
    }

    /**
     * Updates the caller's own company's rule, creating it (seeded from the
     * current defaults) on first customization rather than ever mutating the
     * shared global default out from under every other company. A caller
     * with no company in context (no authenticated principal, or a platform
     * principal) updates the global default itself.
     */
    @Transactional
    public AttendanceRule updateRule(AttendanceRuleRequest request) {
        validate(request);
        AttendanceRule rule = tenantContext.currentCompanyId()
                .map(this::getOrCreateForCompany)
                .orElseGet(this::globalDefault);
        apply(rule, request);
        AttendanceRule saved = attendanceRuleRepository.save(rule);
        auditService.record("ATTENDANCE_RULE_UPDATE", "AttendanceRule",
                saved.getCompany() == null ? "global-default" : String.valueOf(saved.getCompany().getId()),
                AuditOutcome.SUCCESS, "fullDayThresholdPercent=" + saved.getFullDayThresholdPercent()
                        + " halfDayThresholdPercent=" + saved.getHalfDayThresholdPercent());
        return saved;
    }

    /** The half-day cutoff must sit below the full-day one, or every worked day would be a full day. */
    private void validate(AttendanceRuleRequest request) {
        if (request.getHalfDayThresholdPercent().compareTo(request.getFullDayThresholdPercent()) >= 0) {
            throw new BusinessRuleException(
                    "halfDayThresholdPercent must be less than fullDayThresholdPercent");
        }
    }

    private AttendanceRule ruleForCompanyOrDefault(Long companyId) {
        return attendanceRuleRepository.findByCompanyId(companyId).orElseGet(this::globalDefault);
    }

    private AttendanceRule getOrCreateForCompany(Long companyId) {
        return attendanceRuleRepository.findByCompanyId(companyId).orElseGet(() -> {
            AttendanceRule rule = AttendanceRule.defaultRule();
            rule.setCompany(companyRepository.getReferenceById(companyId));
            return rule;
        });
    }

    private AttendanceRule globalDefault() {
        return attendanceRuleRepository.findByCompanyIsNull()
                .orElseGet(() -> attendanceRuleRepository.save(AttendanceRule.defaultRule()));
    }

    private void apply(AttendanceRule rule, AttendanceRuleRequest request) {
        rule.setEntryWindowBufferMinutes(request.getEntryWindowBufferMinutes());
        rule.setFullDayThresholdPercent(request.getFullDayThresholdPercent());
        rule.setHalfDayThresholdPercent(request.getHalfDayThresholdPercent());
    }
}
