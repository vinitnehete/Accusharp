package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.dto.LeaveBalanceResponse;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /** Reads the balance, seeding the default quota on first use. */
    @Transactional
    public LeaveBalance getOrCreate(String userId, int year, LeaveType leaveType) {
        return leaveBalanceRepository.findByUserIdAndLeaveYearAndLeaveType(userId, year, leaveType)
                .orElseGet(() -> leaveBalanceRepository.save(LeaveBalance.builder()
                        .userId(userId)
                        .leaveYear(year)
                        .leaveType(leaveType)
                        .quota(BigDecimal.valueOf(leaveType.getDefaultYearlyQuota()).setScale(1))
                        .used(BigDecimal.ZERO.setScale(1))
                        .build()));
    }

    @Transactional
    public List<LeaveBalanceResponse> getBalances(String userId, int year) {
        employeeService.getEntityByUserId(userId);
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
        LeaveBalance balance = getOrCreate(userId, year, leaveType);
        if (quota.compareTo(balance.getUsed()) < 0) {
            throw new BusinessRuleException("Quota cannot be lower than the " + balance.getUsed()
                    + " days already used");
        }
        balance.setQuota(quota);
        return toResponse(leaveBalanceRepository.save(balance));
    }

    public LeaveBalanceResponse toResponse(LeaveBalance balance) {
        return new LeaveBalanceResponse(balance.getUserId(), balance.getLeaveYear(), balance.getLeaveType(),
                balance.getQuota(), balance.getUsed(), balance.available());
    }
}
