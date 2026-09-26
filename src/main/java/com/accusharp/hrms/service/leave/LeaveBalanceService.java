package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.dto.LeaveBalanceResponse;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveCredit;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Yearly quota per leave type. Balance is never stored - it is always quota
 * minus used, so the two can never disagree.
 */
@Service
@RequiredArgsConstructor
public class LeaveBalanceService {

    private final LeaveBalanceRepository leaveBalanceRepository;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;
    private final LeaveEntitlementService leaveEntitlementService;
    private final LeaveCreditRepository leaveCreditRepository;

    /**
     * Reads the balance, opening it on first use at whatever the employee's
     * leave rules give - see {@link LeaveEntitlementService#openingQuota}. With
     * no rule that is the {@link LeaveType} default, as it always was. A row
     * that already exists is returned as it is, never re-seeded.
     */
    @Transactional
    public LeaveBalance getOrCreate(String userId, int year, LeaveType leaveType) {
        return leaveBalanceRepository.findByUserIdAndLeaveYearAndLeaveType(userId, year, leaveType)
                .orElseGet(() -> leaveBalanceRepository.save(LeaveBalance.builder()
                        .userId(userId)
                        .leaveYear(year)
                        .leaveType(leaveType)
                        .quota(openingQuota(userId, year, leaveType))
                        .used(BigDecimal.ZERO.setScale(1))
                        .build()));
    }

    @Transactional
    public List<LeaveBalanceResponse> getBalances(String userId, int year) {
        employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        return Arrays.stream(LeaveType.values())
                .map(type -> getOrCreate(userId, year, type))
                .map(this::toResponse)
                .toList();
    }

    /** Consumes balance. Unpaid leave has no quota, so it is never blocked. */
    @Transactional
    public void consume(String userId, int year, LeaveType leaveType, BigDecimal days) {
        if (!leaveType.isPaid()) {
            return;
        }
        LeaveBalance balance = getOrCreate(userId, year, leaveType);
        if (balance.available().compareTo(days) < 0) {
            throw new BusinessRuleException("Insufficient " + leaveType + " balance: available "
                    + balance.available() + ", requested " + days);
        }
        balance.setUsed(balance.getUsed().add(days));
        leaveBalanceRepository.save(balance);
    }

    /** Restores balance when an approved leave is cancelled. */
    @Transactional
    public void restore(String userId, int year, LeaveType leaveType, BigDecimal days) {
        if (!leaveType.isPaid()) {
            return;
        }
        LeaveBalance balance = getOrCreate(userId, year, leaveType);
        balance.setUsed(balance.getUsed().subtract(days).max(BigDecimal.ZERO));
        leaveBalanceRepository.save(balance);
    }

    @Transactional
    public LeaveBalanceResponse setQuota(String userId, int year, LeaveType leaveType, BigDecimal quota) {
        employeeService.getEntityByUserId(userId);
        LeaveBalance balance = getOrCreate(userId, year, leaveType);
        if (quota.compareTo(balance.getUsed()) < 0) {
            throw new BusinessRuleException("Quota cannot be lower than the " + balance.getUsed()
                    + " days already used");
        }
        balance.setQuota(quota);
        return toResponse(leaveBalanceRepository.save(balance));
    }

    /**
     * Moves a year's quota by a credit's change - earned-leave accrual and the
     * year-end carry-forward both post through here.
     *
     * <p>Deliberately not floored at {@code used}, unlike {@link #setQuota}. A
     * regenerated month can lower a credit the employee has already spent, and
     * refusing would block the payroll run that recomputed it. The balance then
     * reads negative - visible - rather than the credit silently staying wrong.
     */
    @Transactional
    public LeaveBalance adjustQuota(String userId, int year, LeaveType leaveType, BigDecimal delta) {
        LeaveBalance balance = getOrCreate(userId, year, leaveType);
        if (delta.signum() == 0) {
            return balance;
        }
        balance.setQuota(balance.getQuota().add(delta));
        return leaveBalanceRepository.save(balance);
    }

    /** Every posting onto the year's balances, with the reason for each - "why did I get 1.2?". */
    @Transactional(readOnly = true)
    public List<LeaveCredit> getCredits(String userId, int year) {
        employeeService.getEntityByUserId(userId);
        employeeService.assertSelfOrManages(userId);
        return leaveCreditRepository.findAllByUserIdAndLeaveYearOrderByPeriodAsc(userId, year);
    }

    /**
     * The opening figure for a new balance row. Looked up straight from the
     * repository: every caller has already authorised the target employee, and
     * this only reads their joining date, status and company.
     */
    private BigDecimal openingQuota(String userId, int year, LeaveType leaveType) {
        Employee employee = employeeRepository.findByUserId(userId).orElse(null);
        return leaveEntitlementService.openingQuota(employee, year, leaveType);
    }

    /** The leave year today falls in for this employee's company - what the balances page opens on. */
    @Transactional(readOnly = true)
    public int currentLeaveYear(String userId) {
        Employee employee = employeeRepository.findByUserId(userId).orElse(null);
        return LeaveYears.leaveYearOf(LocalDate.now(), LeaveYears.startMonthOf(employee));
    }

    public LeaveBalanceResponse toResponse(LeaveBalance balance) {
        return new LeaveBalanceResponse(balance.getUserId(), balance.getLeaveYear(), balance.getLeaveType(),
                balance.getQuota(), balance.getUsed(), balance.available());
    }
}
