package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveCredit;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.enums.LeaveCreditKind;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Posts a month's earned leave onto the balance.
 *
 * <p>Called by {@code PayrollService} as it locks the month - the one moment the
 * attendance a credit is computed from can no longer change. Crediting earlier
 * would credit from figures HR may still correct.
 *
 * <p>Idempotent per month: each month has one ledger row in {@code leave_credit}.
 * A repost - payroll regenerated after a correction - replaces the row and moves
 * the balance by the difference, so the credit is fixed rather than stacked.
 *
 * <p>Nothing is credited without an {@code EARNED_BY_ATTENDANCE} rule in effect
 * on the first of the month, and that rule's effective date is the go-live:
 * months before it are never credited, so opening balances entered by hand are
 * never counted twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EarnedLeaveAccrualService {

    private final LeaveRuleResolver leaveRuleResolver;
    private final EarnedLeaveCalculator calculator;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveCreditRepository leaveCreditRepository;

    /** @return the days credited for the month - zero when nothing is earned. */
    @Transactional
    public BigDecimal accrueMonth(Employee employee, YearMonth month, MonthlyAttendanceSummary attendance) {
        if (employee.isContractorWorker()) {
            return BigDecimal.ZERO.setScale(1);
        }
        String userId = employee.getUserId();
        String period = month.toString();
        // March's credit belongs to the year ending in March for an April company.
        int leaveYear = LeaveYears.leaveYearOf(month.atDay(1), LeaveYears.startMonthOf(employee));

        Optional<LeaveRule> rule = leaveRuleResolver.ruleFor(employee, LeaveType.EARNED_LEAVE, month.atDay(1))
                .filter(found -> found.getGrantMethod() == LeaveGrant.EARNED_BY_ATTENDANCE);
        Optional<LeaveCredit> existing = leaveCreditRepository.findByUserIdAndLeaveTypeAndKindAndPeriod(
                userId, LeaveType.EARNED_LEAVE, LeaveCreditKind.MONTHLY_ACCRUAL, period);

        if (rule.isEmpty()) {
            // Nothing is earned this month. A credit an earlier run posted under
            // a rule since removed is taken back, so the ledger always agrees
            // with the rules payroll last ran under.
            existing.ifPresent(stale -> {
                leaveBalanceService.adjustQuota(userId, stale.getLeaveYear(), LeaveType.EARNED_LEAVE,
                        stale.getDays().negate());
                leaveCreditRepository.delete(stale);
            });
            return BigDecimal.ZERO.setScale(1);
        }

        EarnedLeaveCalculator.EarnedLeaveCredit computed = calculator.calculate(rule.get(),
                attendance.getWorkingDays(), attendance.getLopDays(), employedWholeMonth(employee, month));

        BigDecimal previous = existing.map(LeaveCredit::getDays).orElse(BigDecimal.ZERO);
        LeaveCredit credit = existing.orElseGet(() -> LeaveCredit.builder()
                .userId(userId).leaveType(LeaveType.EARNED_LEAVE).kind(LeaveCreditKind.MONTHLY_ACCRUAL)
                .period(period).leaveYear(leaveYear)
                .build());
        credit.setDays(computed.credit());
        credit.setDaysCounted(computed.daysCounted());
        credit.setBasis(computed.basis());
        credit.setPostedAt(Instant.now());
        leaveCreditRepository.save(credit);

        leaveBalanceService.adjustQuota(userId, leaveYear, LeaveType.EARNED_LEAVE,
                computed.credit().subtract(previous));

        log.info("leave.el-accrual userId={} month={} counted={} credit={} previous={}",
                userId, period, computed.daysCounted(), computed.credit(), previous);
        return computed.credit();
    }

    /**
     * On the books from the first of the month to the last. A joiner or leaver
     * with no LOP in the days they were there has still not worked a full
     * month, and a full-month credit would pay half a month as a whole one.
     */
    private boolean employedWholeMonth(Employee employee, YearMonth month) {
        LocalDate joined = employee.getJoiningDate();
        LocalDate relieved = employee.getRelievingDate();
        return (joined == null || !joined.isAfter(month.atDay(1)))
                && (relieved == null || !relieved.isBefore(month.atEndOfMonth()));
    }
}
