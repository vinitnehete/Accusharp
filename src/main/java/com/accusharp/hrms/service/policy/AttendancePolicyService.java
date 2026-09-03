package com.accusharp.hrms.service.policy;

import com.accusharp.hrms.dto.policy.AttendancePolicyDtos;
import com.accusharp.hrms.dto.policy.AttendancePolicyParams;
import com.accusharp.hrms.dto.policy.AttendancePolicyRuleRequest;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.AttendanceRule;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.AttendancePolicyRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.service.AttendanceRuleService;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Managing attendance policy rules - the write side of the engine
 * {@link AttendancePolicyResolver} reads.
 *
 * <p>Follows the tenancy shape SECURITY.md Phase 6 established for {@code
 * Shift} and {@code Category}: a company sees its own rows plus the shared
 * {@code company = null} catalog, and can write only its own. A company can
 * never edit a global row, and never sees another company's.
 *
 * <p>Append-only. There is no update and almost no delete: changing a rule
 * appends a version, ending one appends a disabled version. Only a version that
 * has not started yet may be deleted, because it has never priced a day.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendancePolicyService {

    private final AttendancePolicyRuleRepository ruleRepository;
    private final AttendancePolicyResolver resolver;
    private final AttendancePolicyParamsCodec codec;
    private final DailyAttendanceRepository dailyAttendanceRepository;
    private final AttendanceRuleService attendanceRuleService;
    private final EmployeeService employeeService;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    // ---- read --------------------------------------------------------------

    /** The caller's own company's rules, plus the shared catalog. */
    @Transactional(readOnly = true)
    public List<AttendancePolicyDtos.RuleResponse> list(RuleType ruleType, RuleScope scope) {
        List<AttendancePolicyRule> rules = tenantContext.currentCompanyId()
                .map(id -> {
                    List<AttendancePolicyRule> own = new ArrayList<>(
                            ruleRepository.findAllByCompanyIdOrderByRuleTypeAscScopeAscVersionDesc(id));
                    own.addAll(ruleRepository.findAllByCompanyIsNullOrderByRuleTypeAscScopeAscVersionDesc());
                    return own;
                })
                .orElseGet(ruleRepository::findAllByCompanyIsNullOrderByRuleTypeAscScopeAscVersionDesc);

        return rules.stream()
                .filter(rule -> ruleType == null || rule.getRuleType() == ruleType)
                .filter(rule -> scope == null || rule.getScope() == scope)
                .sorted(Comparator.comparing((AttendancePolicyRule r) -> r.getRuleType().name())
                        .thenComparing(r -> r.getScope().ordinal())
                        .thenComparing(AttendancePolicyRule::getScopeRef)
                        .thenComparing(Comparator.comparingInt(AttendancePolicyRule::getVersion).reversed()))
                .map(AttendancePolicyDtos.RuleResponse::of)
                .toList();
    }

    /**
     * What actually applies to one employee on one date, with the rules that
     * matched and lost alongside - see {@link AttendancePolicyDtos.EffectiveRule}.
     */
    @Transactional(readOnly = true)
    public AttendancePolicyDtos.EffectivePolicyResponse effectivePolicy(String userId, LocalDate date) {
        // Resolved through the same choke point every cross-company check uses,
        // so naming another company's userId 404s exactly as an unknown one does.
        Employee employee = employeeService.getEntityByUserId(userId);
        Long companyId = employee.getCompany() == null ? null : employee.getCompany().getId();

        List<AttendancePolicyRule> companyRules = resolver.loadCompanyRules(companyId, date);
        ResolvedPolicy resolved = resolver.resolve(companyRules, employee, date);

        List<AttendancePolicyDtos.EffectiveRule> rules = new ArrayList<>();
        for (RuleType ruleType : RuleType.values()) {
            List<AttendancePolicyRule> candidates =
                    resolver.candidatesFor(companyRules, employee, date, ruleType);
            Optional<AttendancePolicyRule> applied = resolved.rule(ruleType);

            List<AttendancePolicyDtos.RuleResponse> beaten = candidates.stream()
                    .filter(rule -> applied.isEmpty() || !rule.getId().equals(applied.get().getId()))
                    .map(AttendancePolicyDtos.RuleResponse::of)
                    .toList();

            rules.add(new AttendancePolicyDtos.EffectiveRule(
                    ruleType, ruleType.evaluationScope(),
                    applied.map(AttendancePolicyDtos.RuleResponse::of).orElse(null),
                    explainOutcome(candidates, applied),
                    beaten));
        }

        AttendanceRule base = attendanceRuleService.getActiveRuleForCompany(companyId);

        return new AttendancePolicyDtos.EffectivePolicyResponse(
                employee.getUserId(), date,
                employee.getCategory() == null ? null : employee.getCategory().getCategoryCode(),
                employee.getDepartment() == null ? null : employee.getDepartment().getDepartmentCode(),
                employee.getDesignation() == null ? null : employee.getDesignation().getDesignationCode(),
                employee.getStatus() == null ? null : employee.getStatus().name(),
                new AttendancePolicyDtos.BaseThresholds(base.getEntryWindowBufferMinutes(),
                        base.getFullDayThresholdPercent(), base.getHalfDayThresholdPercent()),
                rules);
    }

    private String explainOutcome(List<AttendancePolicyRule> candidates,
                                  Optional<AttendancePolicyRule> applied) {
        if (applied.isPresent()) {
            return "most specific match: " + applied.get().scopeLabel();
        }
        if (candidates.isEmpty()) {
            return "no rule of this type is configured for this employee - today's built-in behaviour applies";
        }
        // Candidates exist but nothing applied, which can only mean the most
        // specific one is disabled. Saying so beats an empty result that reads
        // identically to "nothing configured".
        return "the most specific match (" + candidates.getFirst().scopeLabel()
                + ") is disabled, so this rule type does not apply to this employee";
    }

    // ---- write -------------------------------------------------------------

    /**
     * Appends the next version of a rule.
     *
     * <p>Four things are checked before anything is written, in this order:
     * the parameters bind and validate against their type's record; the scope
     * reference names something that exists; the effective date does not reach
     * into a locked period; and the (scope, ref, type, date) tuple is free -
     * that last one enforced by {@code uk_policy_rule_effective} rather than
     * only here, because a check-then-act in a service cannot stop two
     * concurrent inserts.
     */
    @Transactional
    public AttendancePolicyDtos.RuleResponse create(AttendancePolicyRuleRequest request) {
        RuleType ruleType = request.getRuleType();
        RuleScope scope = request.getScope();
        String scopeRef = normaliseScopeRef(scope, request.getScopeRef());

        // Bind and validate before anything else - a bad params blob should be a
        // 400 naming the field, not a rule that runs on a zero grace next month.
        AttendancePolicyParams.Params params = codec.parse(ruleType, request.getParams().toString());

        Long companyId = tenantContext.currentCompanyId().orElse(null);
        assertScopeRefExists(scope, scopeRef, companyId);
        assertNotBackdatedIntoLockedPeriod(request.getEffectiveFrom(), scope, scopeRef, companyId);

        int nextVersion = currentHead(companyId, scope, scopeRef, ruleType)
                .map(rule -> rule.getVersion() + 1)
                .orElse(1);

        AttendancePolicyRule rule = AttendancePolicyRule.builder()
                .company(companyId == null ? null : companyRepository.getReferenceById(companyId))
                .scope(scope).scopeRef(scopeRef).ruleType(ruleType)
                .version(nextVersion)
                .effectiveFrom(request.getEffectiveFrom())
                .enabled(request.isEnabled())
                .params(codec.write(params))
                .createdAt(Instant.now())
                .createdBy(tenantContext.currentPrincipal().map(p -> p.getUsername()).orElse(null))
                .notes(request.getNotes())
                .build();

        AttendancePolicyRule saved = ruleRepository.save(rule);

        log.info("attendance.policy.create rule={} company={} effectiveFrom={} enabled={}",
                saved.ruleLabel(), companyId, saved.getEffectiveFrom(), saved.isEnabled());
        auditService.record("ATTENDANCE_POLICY_RULE_CREATE", "AttendancePolicyRule",
                String.valueOf(saved.getId()), AuditOutcome.SUCCESS,
                saved.ruleLabel() + " effectiveFrom=" + saved.getEffectiveFrom()
                        + " enabled=" + saved.isEnabled() + " params=" + saved.getParams());

        return AttendancePolicyDtos.RuleResponse.of(saved);
    }

    /**
     * Deletes a version that has not taken effect yet.
     *
     * <p>Anything that has ever been in force is superseded, never deleted:
     * a day priced under it may already be on a payslip, and the trace rows
     * that explain that day point at this id. A future version has priced
     * nothing, so removing it loses nothing.
     */
    @Transactional
    public void delete(Long id) {
        AttendancePolicyRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Attendance policy rule", id));

        Long companyId = tenantContext.currentCompanyId().orElse(null);
        assertWritable(rule, companyId);

        if (!rule.isFuture(LocalDate.now())) {
            throw new BusinessRuleException("attendance policy rule " + id + " (" + rule.ruleLabel()
                    + ") took effect on " + rule.getEffectiveFrom()
                    + " and may have priced days already - supersede it with a new version"
                    + " (set enabled=false to stop it applying) rather than deleting it");
        }

        ruleRepository.delete(rule);
        auditService.record("ATTENDANCE_POLICY_RULE_DELETE", "AttendancePolicyRule",
                String.valueOf(id), AuditOutcome.SUCCESS,
                rule.ruleLabel() + " effectiveFrom=" + rule.getEffectiveFrom() + " (not yet in force)");
    }

    // ---- guards ------------------------------------------------------------

    /**
     * Refuses a version that reaches back into a period payroll has locked.
     *
     * <p>This is the whole of the "what happens when HR edits a rule for a month
     * that has already been paid" question, and it is narrow on purpose.
     * Because a version resolves by <em>attendance date</em>, a rule dated
     * forward cannot change a past month at all - replay finds the same rows and
     * produces the same numbers - so refusing the recompute would be refusing
     * the harmless case. Only a back-dated version can move a paid month, and
     * that is what this refuses.
     *
     * <p>The alternative is worse than it sounds: {@code ReportService} calls
     * {@code syncSummaries}, which persists, so without this guard merely
     * <em>running a report</em> would silently re-price a locked month under the
     * new rule.
     */
    private void assertNotBackdatedIntoLockedPeriod(LocalDate effectiveFrom, RuleScope scope,
                                                    String scopeRef, Long companyId) {
        Set<String> affected = affectedUserIds(scope, scopeRef, companyId);
        if (affected.isEmpty()) {
            return;
        }

        List<DailyAttendance> locked = dailyAttendanceRepository
                .findTop50ByUserIdInAndAttendanceDateGreaterThanEqualAndLockedTrueOrderByAttendanceDateAsc(
                        affected, effectiveFrom);
        if (locked.isEmpty()) {
            return;
        }

        Set<String> months = locked.stream()
                .map(day -> java.time.YearMonth.from(day.getAttendanceDate()).toString())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        Set<String> employees = locked.stream()
                .map(DailyAttendance::getUserId)
                .collect(Collectors.toSet());

        throw new BusinessRuleException("effectiveFrom " + effectiveFrom
                + " falls inside a locked period (" + String.join(", ", months)
                + " is paid for " + employees.size() + " employee(s) in this scope)"
                + " - a rule cannot re-price a month that has already been paid."
                + " Unlock the month, or date this version after it.");
    }

    /**
     * A scope reference that names nothing is completely silent otherwise - the
     * rule simply never matches an employee, and HR spends a month wondering why
     * the policy has no effect.
     */
    private void assertScopeRefExists(RuleScope scope, String scopeRef, Long companyId) {
        if (!scope.requiresRef()) {
            return;
        }
        if (scope == RuleScope.EMPLOYEE) {
            employeeService.getEntityByUserId(scopeRef); // 404s cross-company, same as everywhere else
            return;
        }
        if (scope == RuleScope.EMPLOYMENT_TYPE) {
            try {
                com.accusharp.hrms.enums.EmployeeStatus.valueOf(scopeRef);
            } catch (IllegalArgumentException ex) {
                throw new BusinessRuleException("scopeRef '" + scopeRef + "' is not an employment type - expected one of "
                        + java.util.Arrays.toString(com.accusharp.hrms.enums.EmployeeStatus.values()));
            }
            return;
        }
        boolean matches = employeeService.getAllEntities().stream().anyMatch(employee -> switch (scope) {
            case CATEGORY -> employee.getCategory() != null
                    && scopeRef.equals(employee.getCategory().getCategoryCode());
            case DEPARTMENT -> employee.getDepartment() != null
                    && scopeRef.equals(employee.getDepartment().getDepartmentCode());
            case DESIGNATION -> employee.getDesignation() != null
                    && scopeRef.equals(employee.getDesignation().getDesignationCode());
            default -> false;
        });
        if (!matches) {
            throw new BusinessRuleException("no employee in this company has " + scope + " '" + scopeRef
                    + "' - check the code, or the rule will silently never apply");
        }
    }

    /** A company may only write its own rows; the shared catalog is platform-only. */
    private void assertWritable(AttendancePolicyRule rule, Long companyId) {
        Long owner = rule.getCompany() == null ? null : rule.getCompany().getId();
        if (companyId == null) {
            // A platform caller writes the shared catalog, never a company's rows.
            if (owner != null) {
                throw NotFoundException.of("Attendance policy rule", rule.getId());
            }
            return;
        }
        if (!companyId.equals(owner)) {
            // 404, not 403 - the same shape a nonexistent id returns, so the
            // response never confirms another company's row exists.
            throw NotFoundException.of("Attendance policy rule", rule.getId());
        }
    }

    private Set<String> affectedUserIds(RuleScope scope, String scopeRef, Long companyId) {
        List<Employee> employees = employeeService.getAllEntities();
        return employees.stream()
                .filter(employee -> switch (scope) {
                    case GLOBAL, COMPANY -> true;
                    case EMPLOYEE -> scopeRef.equals(employee.getUserId());
                    case EMPLOYMENT_TYPE -> employee.getStatus() != null
                            && scopeRef.equals(employee.getStatus().name());
                    case CATEGORY -> employee.getCategory() != null
                            && scopeRef.equals(employee.getCategory().getCategoryCode());
                    case DEPARTMENT -> employee.getDepartment() != null
                            && scopeRef.equals(employee.getDepartment().getDepartmentCode());
                    case DESIGNATION -> employee.getDesignation() != null
                            && scopeRef.equals(employee.getDesignation().getDesignationCode());
                })
                .map(Employee::getUserId)
                .collect(Collectors.toSet());
    }

    private String normaliseScopeRef(RuleScope scope, String scopeRef) {
        if (!scope.requiresRef()) {
            return RuleScope.ANY;
        }
        if (scopeRef == null || scopeRef.isBlank()) {
            throw new BusinessRuleException("scopeRef is required for scope " + scope);
        }
        return scopeRef.trim();
    }

    private Optional<AttendancePolicyRule> currentHead(Long companyId, RuleScope scope,
                                                       String scopeRef, RuleType ruleType) {
        return companyId == null
                ? ruleRepository.findFirstByCompanyIsNullAndScopeAndScopeRefAndRuleTypeOrderByVersionDesc(
                        scope, scopeRef, ruleType)
                : ruleRepository.findFirstByCompanyIdAndScopeAndScopeRefAndRuleTypeOrderByVersionDesc(
                        companyId, scope, scopeRef, ruleType);
    }
}
