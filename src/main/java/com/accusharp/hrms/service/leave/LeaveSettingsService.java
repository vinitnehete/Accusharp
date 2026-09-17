package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.dto.LeaveSettingsRequest;
import com.accusharp.hrms.dto.LeaveSettingsResponse;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.security.TenantContext;
import com.accusharp.hrms.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * A company's leave year - January (calendar) or April (financial) - read by
 * anyone in the company and changed only by its HR.
 *
 * <h2>When it can be switched</h2>
 *
 * <p>Only while the company has no leave taken, pending or credited. Leave
 * already taken came out of a balance numbered by the old year; after a switch,
 * cancelling it would give the days back to a different balance, and a month's
 * earned leave would have landed in a year that no longer means what it did.
 *
 * <p>A balance that merely <em>exists</em> does not block it. Balances open
 * automatically the first time anyone looks at them, so almost every company
 * has some; one nobody has used carries no history to misplace, and simply
 * becomes the first year under the new numbering.
 */
@Service
@RequiredArgsConstructor
public class LeaveSettingsService {

    private static final Set<Integer> ALLOWED_START_MONTHS = Set.of(1, 4);
    private static final List<LeaveStatus> LIVE_STATUSES =
            List.of(LeaveStatus.PENDING, LeaveStatus.SUPERVISOR_APPROVED, LeaveStatus.APPROVED);

    private final TenantContext tenantContext;
    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveCreditRepository leaveCreditRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public LeaveSettingsResponse get() {
        return toResponse(callerCompany());
    }

    @Transactional
    public LeaveSettingsResponse update(LeaveSettingsRequest request) {
        Company company = callerCompany();
        int month = request.getLeaveYearStartMonth();
        if (!ALLOWED_START_MONTHS.contains(month)) {
            throw new BusinessRuleException("The leave year starts in January (1) or April (4), got " + month);
        }
        if (month != company.getLeaveYearStartMonth()) {
            String blocked = blockedBecause(company);
            if (blocked != null) {
                throw new BusinessRuleException("The leave year cannot be switched: " + blocked);
            }
            company.setLeaveYearStartMonth(month);
            companyRepository.save(company);
            auditService.record("LEAVE_SETTINGS_UPDATE", "Company", String.valueOf(company.getId()),
                    AuditOutcome.SUCCESS, "leaveYearStartMonth=" + month);
        }
        return toResponse(company);
    }

    private Company callerCompany() {
        Long companyId = tenantContext.currentCompanyId().orElseThrow(() -> new BusinessRuleException(
                "Leave settings belong to a company - sign in as that company's HR to change them"));
        return companyRepository.findById(companyId).orElseThrow(() -> NotFoundException.of("Company", companyId));
    }

    /** Null when the leave year can be switched; otherwise the reason it cannot. */
    private String blockedBecause(Company company) {
        List<String> userIds = employeeRepository.findByCompanyId(company.getId()).stream()
                .map(Employee::getUserId).toList();
        if (userIds.isEmpty()) {
            return null;
        }
        if (leaveRequestRepository.existsByUserIdInAndStatusIn(userIds, LIVE_STATUSES)
                || leaveBalanceRepository.existsByUserIdInAndUsedGreaterThan(userIds, BigDecimal.ZERO)) {
            return "leave has already been applied for or taken under the current leave year";
        }
        if (leaveCreditRepository.existsByUserIdIn(userIds)) {
            return "earned leave or a carry-forward has already been credited under the current leave year";
        }
        return null;
    }

    private LeaveSettingsResponse toResponse(Company company) {
        int startMonth = company.getLeaveYearStartMonth();
        int current = LeaveYears.leaveYearOf(LocalDate.now(), startMonth);
        String blocked = blockedBecause(company);
        return new LeaveSettingsResponse(startMonth, blocked == null, current,
                LeaveYears.label(current, startMonth), blocked);
    }
}
