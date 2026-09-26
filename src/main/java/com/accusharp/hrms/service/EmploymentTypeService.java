package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.EmploymentTypeRequest;
import com.accusharp.hrms.entity.EmploymentType;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.PayBasis;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.EmploymentTypeRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.service.payroll.PayBehaviourResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Employment types, per company, following the same shared-catalog pattern as
 * {@code Shift} and {@code Category} (SECURITY.md Phase 6): a company sees its
 * own rows plus the {@code company = null} catalog, and writes only its own.
 *
 * <p>Unlike those two, the shared catalog here is seeded with the four types
 * that reproduce today's {@link EmployeeStatus} behaviour exactly - so a company
 * that wants to start using configurable types has a correct starting point
 * rather than a blank page, and one that never touches them is unaffected
 * because an employee with no type assigned still falls back to the enum. See
 * {@link PayBehaviourResolver}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmploymentTypeService {

    private final EmploymentTypeRepository employmentTypeRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    /** The caller's own company's types, plus the shared catalog. */
    @Transactional(readOnly = true)
    public List<EmploymentType> getAll() {
        List<EmploymentType> all = new ArrayList<>(
                employmentTypeRepository.findAllByCompanyIsNullOrderByTypeCodeAsc());
        tenantContext.currentCompanyId()
                .map(employmentTypeRepository::findAllByCompanyIdOrderByTypeCodeAsc)
                .ifPresent(all::addAll);
        return all;
    }

    @Transactional(readOnly = true)
    public EmploymentType getById(Long id) {
        EmploymentType type = employmentTypeRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Employment type", id));
        assertReadable(type);
        return type;
    }

    @Transactional
    public EmploymentType create(EmploymentTypeRequest request) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        assertCodeIsFree(companyId, request.getTypeCode());
        validate(request);

        EmploymentType type = new EmploymentType();
        type.setCompany(companyId == null ? null : companyRepository.getReferenceById(companyId));
        apply(type, request);

        EmploymentType saved = employmentTypeRepository.save(type);
        audit("EMPLOYMENT_TYPE_CREATE", saved);
        return saved;
    }

    @Transactional
    public EmploymentType update(Long id, EmploymentTypeRequest request) {
        EmploymentType type = employmentTypeRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Employment type", id));
        assertWritable(type);
        validate(request);

        if (!type.getTypeCode().equals(request.getTypeCode())) {
            assertCodeIsFree(type.getCompany() == null ? null : type.getCompany().getId(),
                    request.getTypeCode());
        }
        apply(type, request);

        EmploymentType saved = employmentTypeRepository.save(type);
        audit("EMPLOYMENT_TYPE_UPDATE", saved);
        return saved;
    }

    /**
     * Deactivates rather than deletes once anybody is on the type.
     *
     * <p>A type in use is referenced by employee rows and, through the payroll
     * snapshot, by every payslip computed under it. Deleting it would orphan
     * both. Deactivating stops it being assigned to anyone new while leaving
     * history intact - the same reason {@code Payroll} is superseded rather
     * than edited.
     */
    @Transactional
    public void delete(Long id) {
        EmploymentType type = employmentTypeRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Employment type", id));
        assertWritable(type);

        long inUse = employeeRepository.countByEmploymentTypeId(id);
        if (inUse > 0) {
            throw new BusinessRuleException("employment type " + type.getTypeCode() + " is assigned to "
                    + inUse + " employee(s) and cannot be deleted - set active=false instead,"
                    + " which stops it being assigned to anyone new while leaving their payroll history intact");
        }
        employmentTypeRepository.delete(type);
        audit("EMPLOYMENT_TYPE_DELETE", type);
    }

    /**
     * Seeds the four types that reproduce today's {@link EmployeeStatus}
     * behaviour, for a company adopting configurable types.
     *
     * <p>Idempotent, and never overwrites a type that already exists - a company
     * that has already customised {@code DAY_WISE} must not have it reset by
     * somebody calling this again.
     */
    @Transactional
    public List<EmploymentType> seedDefaults() {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        List<EmploymentType> seeded = new ArrayList<>();

        for (EmployeeStatus status : EmployeeStatus.values()) {
            boolean exists = companyId == null
                    ? employmentTypeRepository.findByCompanyIsNullAndTypeCode(status.name()).isPresent()
                    : employmentTypeRepository.findByCompanyIdAndTypeCode(companyId, status.name()).isPresent();
            if (exists) {
                continue;
            }
            EmploymentType type = PayBehaviourResolver.seedFor(status);
            type.setCompany(companyId == null ? null : companyRepository.getReferenceById(companyId));
            seeded.add(employmentTypeRepository.save(type));
        }

        log.info("employment-type.seed company={} created={}", companyId, seeded.size());
        auditService.record("EMPLOYMENT_TYPE_SEED", "EmploymentType",
                companyId == null ? "shared-catalog" : String.valueOf(companyId),
                AuditOutcome.SUCCESS, "created=" + seeded.size());
        return seeded;
    }

    // ---- guards ------------------------------------------------------------

    /**
     * A per-attended-day type with loss of pay enabled would charge an employee
     * for days they were never going to be paid for in the first place - the
     * attendance shortfall is already reflected in the days they are paid.
     */
    private void validate(EmploymentTypeRequest request) {
        if (request.getPayBasis() == PayBasis.PER_ATTENDED_DAY && request.isLopApplies()) {
            throw new BusinessRuleException("a PER_ATTENDED_DAY type cannot also apply loss of pay:"
                    + " attendance already decides what is paid, so LOP would deduct the same"
                    + " absence twice");
        }
        if (request.getPayBasis() == PayBasis.PER_CALENDAR_DAY_LESS_LOP
                && request.getPayableDaysCap() != null) {
            throw new BusinessRuleException("payableDaysCap applies only to a PER_ATTENDED_DAY type -"
                    + " a calendar-day type prorates against the month's own length");
        }
    }

    private void assertCodeIsFree(Long companyId, String typeCode) {
        boolean taken = employmentTypeRepository.findByCompanyIsNullAndTypeCode(typeCode).isPresent()
                || (companyId != null
                    && employmentTypeRepository.findByCompanyIdAndTypeCode(companyId, typeCode).isPresent());
        if (taken) {
            throw new BusinessRuleException("employment type code '" + typeCode
                    + "' already exists for this company or in the shared catalog");
        }
    }

    /** A company sees its own rows plus the shared catalog; anything else 404s. */
    private void assertReadable(EmploymentType type) {
        Long owner = type.getCompany() == null ? null : type.getCompany().getId();
        if (owner == null) {
            return;
        }
        Long caller = tenantContext.currentCompanyId().orElse(null);
        if (!owner.equals(caller)) {
            throw NotFoundException.of("Employment type", type.getId());
        }
    }

    /**
     * Only the caller's own company's rows are writable. A company can never
     * edit a shared row - the exact gap Phase 6 closed for {@code Shift}, and
     * for the same reason: an in-place edit to a shared type's pay basis would
     * silently change what every company using it pays.
     */
    private void assertWritable(EmploymentType type) {
        Long owner = type.getCompany() == null ? null : type.getCompany().getId();
        Long caller = tenantContext.currentCompanyId().orElse(null);
        if (caller == null ? owner != null : !caller.equals(owner)) {
            throw NotFoundException.of("Employment type", type.getId());
        }
    }

    private void apply(EmploymentType type, EmploymentTypeRequest request) {
        type.setTypeCode(request.getTypeCode());
        type.setTypeName(request.getTypeName());
        type.setPayBasis(request.getPayBasis());
        type.setPayableDaysCap(request.getPayableDaysCap());
        type.setLopApplies(request.isLopApplies());
        type.setPaidLeaveAddsPayableDays(request.isPaidLeaveAddsPayableDays());
        type.setOvertimeBasis(request.getOvertimeBasis());
        type.setPaidLeaveEarnsOvertime(request.isPaidLeaveEarnsOvertime());
        type.setSegmentedRevisionEarnings(request.isSegmentedRevisionEarnings());
        type.setAutoRosterDefaultShift(request.isAutoRosterDefaultShift());
        type.setActive(request.isActive());
    }

    private void audit(String action, EmploymentType type) {
        auditService.record(action, "EmploymentType", String.valueOf(type.getId()),
                AuditOutcome.SUCCESS,
                "code=" + type.getTypeCode() + " payBasis=" + type.getPayBasis()
                        + " cap=" + type.getPayableDaysCap() + " lopApplies=" + type.isLopApplies()
                        + " overtimeBasis=" + type.getOvertimeBasis()
                        + " paidLeaveEarnsOvertime=" + type.isPaidLeaveEarnsOvertime()
                        + " active=" + type.isActive());
    }
}
