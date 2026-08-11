package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.SalaryRuleRequest;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import com.accusharp.hrms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payroll formula, now per company rather than one row every tenant
 * shared - see {@link SalaryRule}'s Javadoc for why that was a real bug.
 *
 * <p>A company without its own rule transparently falls back to the global
 * default (seeded with sane values the first time anyone asks) - so an
 * existing company that has never customized its formula behaves exactly as
 * it did before this class existed. Only {@link #updateRule} ever creates a
 * company-specific row, and only for the caller's own company.
 */
@Service
@RequiredArgsConstructor
public class SalaryRuleService {

    private final SalaryRuleRepository salaryRuleRepository;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    /** The caller's own company's rule if one exists, else the global default. */
    @Transactional
    public SalaryRule getActiveRule() {
        return tenantContext.currentCompanyId()
                .map(this::ruleForCompanyOrDefault)
                .orElseGet(this::globalDefault);
    }

    /** Same resolution as {@link #getActiveRule}, but for a specific employee's company rather than the caller's. */
    @Transactional
    public SalaryRule getActiveRuleForCompany(Company company) {
        return company == null ? globalDefault() : ruleForCompanyOrDefault(company.getId());
    }

    /**
     * Updates the caller's own company's rule, creating it (seeded from the
     * current defaults) on first customization rather than ever mutating the
     * shared global default out from under every other company. A caller
     * with no company in context (no authenticated principal, or a platform
     * principal) updates the global default itself - the same behavior this
     * class had before per-company rules existed.
     */
    @Transactional
    public SalaryRule updateRule(SalaryRuleRequest request) {
        SalaryRule rule = tenantContext.currentCompanyId()
                .map(this::getOrCreateForCompany)
                .orElseGet(this::globalDefault);
        apply(rule, request);
        SalaryRule saved = salaryRuleRepository.save(rule);
        auditService.record("SALARY_RULE_UPDATE", "SalaryRule",
                saved.getCompany() == null ? "global-default" : String.valueOf(saved.getCompany().getId()),
                AuditOutcome.SUCCESS, "basicDaPercent=" + saved.getBasicDaPercent());
        return saved;
    }

    private SalaryRule ruleForCompanyOrDefault(Long companyId) {
        return salaryRuleRepository.findByCompanyId(companyId).orElseGet(this::globalDefault);
    }

    private SalaryRule getOrCreateForCompany(Long companyId) {
        return salaryRuleRepository.findByCompanyId(companyId).orElseGet(() -> {
            SalaryRule rule = SalaryRule.defaultRule();
            rule.setCompany(companyRepository.getReferenceById(companyId));
            return rule;
        });
    }

    private SalaryRule globalDefault() {
        return salaryRuleRepository.findByCompanyIsNull()
                .orElseGet(() -> salaryRuleRepository.save(SalaryRule.defaultRule()));
    }

    private void apply(SalaryRule rule, SalaryRuleRequest request) {
        rule.setBasicDaPercent(request.getBasicDaPercent());
        rule.setBasicDaMinimumThreshold(request.getBasicDaMinimumThreshold());
        rule.setHraPercent(request.getHraPercent());
        rule.setConveyancePercent(request.getConveyancePercent());
        rule.setEducationPercent(request.getEducationPercent());
        rule.setPfPercent(request.getPfPercent());
        rule.setEsicPercent(request.getEsicPercent());
        rule.setEsicWageCeiling(request.getEsicWageCeiling());
        rule.setPtUpperThreshold(request.getPtUpperThreshold());
        rule.setPtUpperAmount(request.getPtUpperAmount());
        rule.setPtLowerThreshold(request.getPtLowerThreshold());
        rule.setPtLowerAmount(request.getPtLowerAmount());
        rule.setDayWiseDaysInMonth(request.getDayWiseDaysInMonth());
        rule.setStandardHoursPerDay(request.getStandardHoursPerDay());
        rule.setOvertimeRateMultiplier(request.getOvertimeRateMultiplier());
        rule.setMlwfAmount(request.getMlwfAmount());
    }
}
