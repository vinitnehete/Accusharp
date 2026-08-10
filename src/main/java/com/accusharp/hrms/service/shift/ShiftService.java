package com.accusharp.hrms.service.shift;

import com.accusharp.hrms.dto.ShiftRequest;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Shift master. The four standard shifts are seeded at startup with {@code
 * company = null} - a shared catalog every company can see and roster
 * against but never edit; admins add their own company's custom shifts on
 * top through this service. See {@code Department}'s Javadoc for the full
 * company/null rationale, mirrored here identically.
 */
@Service
@RequiredArgsConstructor
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;

    @Transactional
    public Shift create(ShiftRequest request) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        if (codeInUse(request.getShiftCode(), companyId)) {
            throw new ConflictException("Shift already exists with code " + request.getShiftCode());
        }
        Shift shift = apply(new Shift(), request);
        shift.setCompany(companyId == null ? null : companyRepository.getReferenceById(companyId));
        return shiftRepository.save(shift);
    }

    @Transactional
    public Shift update(Long id, ShiftRequest request) {
        Shift shift = getById(id);
        assertWritable(shift);
        Long companyId = shift.getCompany() == null ? null : shift.getCompany().getId();
        findByCode(request.getShiftCode(), companyId)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another shift already uses code " + request.getShiftCode());
                });
        return shiftRepository.save(apply(shift, request));
    }

    @Transactional(readOnly = true)
    public Shift getById(Long id) {
        Shift shift = shiftRepository.findById(id).orElseThrow(() -> NotFoundException.of("Shift", id));
        assertReadable(shift);
        return shift;
    }

    /** The caller's own company's shift with this code, falling back to a shared shift. */
    @Transactional(readOnly = true)
    public Shift getByCode(String shiftCode, Long companyId) {
        return findByCodeIfPresent(shiftCode, companyId)
                .orElseThrow(() -> NotFoundException.of("Shift", "code " + shiftCode));
    }

    /**
     * Same resolution as {@link #getByCode}, but empty instead of throwing
     * when nothing matches. For callers where a missing shift means "skip
     * this", not "fail this request" - throwing from inside a transaction a
     * caller doesn't own marks it rollback-only the instant it's thrown, so
     * a try/catch around {@link #getByCode} in that situation is too late.
     */
    @Transactional(readOnly = true)
    public Optional<Shift> findByCodeIfPresent(String shiftCode, Long companyId) {
        if (companyId != null) {
            Optional<Shift> own = shiftRepository.findByShiftCodeAndCompanyId(shiftCode, companyId);
            if (own.isPresent()) {
                return own;
            }
        }
        return shiftRepository.findByShiftCodeAndCompanyIsNull(shiftCode);
    }

    @Transactional(readOnly = true)
    public List<Shift> getAll() {
        return tenantContext.currentCompanyId()
                .map(shiftRepository::findByCompanyIdOrCompanyIsNull)
                .orElseGet(shiftRepository::findAll);
    }

    /** Refused while any roster still points at the shift. */
    @Transactional
    public void delete(Long id) {
        Shift shift = getById(id);
        assertWritable(shift);
        if (shiftScheduleRepository.countByShiftId(id) > 0) {
            throw new ConflictException("Shift is still used by existing schedules");
        }
        shiftRepository.delete(shift);
    }

    private boolean codeInUse(String code, Long companyId) {
        if (companyId == null) {
            return shiftRepository.existsByShiftCodeAndCompanyIsNull(code);
        }
        return shiftRepository.existsByShiftCodeAndCompanyId(code, companyId)
                || shiftRepository.existsByShiftCodeAndCompanyIsNull(code);
    }

    private Optional<Shift> findByCode(String code, Long companyId) {
        if (companyId == null) {
            return shiftRepository.findByShiftCodeAndCompanyIsNull(code);
        }
        return shiftRepository.findByShiftCodeAndCompanyId(code, companyId)
                .or(() -> shiftRepository.findByShiftCodeAndCompanyIsNull(code));
    }

    /** A caller scoped to one company may read their own company's rows and every shared row. */
    private void assertReadable(Shift shift) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = shift.getCompany() == null ? null : shift.getCompany().getId();
            if (targetCompanyId != null && !callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Shift", shift.getId());
            }
        });
    }

    /** Unlike read access, a shared (company-null) row is never writable by a company caller. */
    private void assertWritable(Shift shift) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = shift.getCompany() == null ? null : shift.getCompany().getId();
            if (!callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Shift", shift.getId());
            }
        });
    }

    private Shift apply(Shift shift, ShiftRequest request) {
        shift.setShiftCode(request.getShiftCode());
        shift.setShiftName(request.getShiftName());
        shift.setStartTime(request.getStartTime());
        shift.setEndTime(request.getEndTime());
        shift.setWorkingHours(request.getWorkingHours());
        shift.setBreakMinutes(request.getBreakMinutes());
        shift.setGraceMinutes(request.getGraceMinutes());
        shift.setOvertimeWindowMinutes(request.getOvertimeWindowMinutes());
        return shift;
    }
}
