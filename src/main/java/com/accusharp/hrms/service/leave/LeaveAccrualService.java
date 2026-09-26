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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Posts a month's leave accrual onto the balance.
 *
 * <p>Called by {@code PayrollService} as it locks the month - the one moment the
 * attendance a credit is computed from can no longer change. Crediting earlier
 * would credit from figures HR may still correct.
 *
 * <p>Two kinds of accrual, one ledger:
 * <ul>
 *   <li><b>Earned leave from attendance</b> - days counted, steps and the legal
 *       floor, in {@link EarnedLeaveCalculator}.</li>
 *   <li><b>A monthly accrual</b> of any paid type - a flat credit for each whole
 *       month on the books, which is how most companies actually run casual and
 *       sick leave. Attendance is not consulted.</li>
 * </ul>
 *
 * <p>Idempotent per month and type: each has one row in {@code leave_credit}. A
 * repost - payroll regenerated after a correction - replaces the row and moves
 * the balance by the difference, so a credit is fixed rather than stacked.
 *
 * <p>Nothing is credited without a rule in effect on the first of the month, and
 * that rule's effective date is the go-live: months before it are never
 * credited, so opening balances entered by hand are never counted twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveAccrualService {

    private final LeaveRuleResolver leaveRuleResolver;
    private final EarnedLeaveCalculator calculator;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveCreditRepository leaveCreditRepository;

    /** @return the days credited for the month across every leave type - zero when nothing is earned. */
    @Transactional
    public BigDecimal accrueMonth(Employee employee, YearMonth month, MonthlyAttendanceSummary attendance) {
        if (employee.isContractorWorker()) {
            return zero();
        }
        BigDecimal credited = zero();
        for (LeaveType leaveType : LeaveType.values()) {
            if (leaveType.isPaid()) {
                credited = credited.add(accrueMonth(employee, month, attendance, leaveType));
            }
        }
        return credited;
    }

    private BigDecimal accrueMonth(Employee employee, YearMonth month, MonthlyAttendanceSummary attendance,
                                   LeaveType leaveType) {
        String userId = employee.getUserId();
        String period = month.toString();
        // March's credit belongs to the year ending in March for an April company.
        int leaveYear = LeaveYears.leaveYearOf(month.atDay(1), LeaveYears.startMonthOf(employee));

        Optional<LeaveRule> rule = leaveRuleResolver.ruleFor(employee, leaveType, month.atDay(1))
                .filter(LeaveAccrualService::accrues);
        Optional<LeaveCredit> existing = leaveCreditRepository.findByUserIdAndLeaveTypeAndKindAndPeriod(
                userId, leaveType, LeaveCreditKind.MONTHLY_ACCRUAL, period);

        if (rule.isEmpty()) {
            // Nothing is earned this month. A credit an earlier run posted under
            // a rule since removed is taken back, so the ledger always agrees
            // with the rules payroll last ran under.
            existing.ifPresent(stale -> {
                leaveBalanceService.adjustQuota(userId, stale.getLeaveYear(), leaveType, stale.getDays().negate());
                leaveCreditRepository.delete(stale);
            });
            return zero();
        }

        Accrual computed = compute(rule.get(), employee, month, attendance);
        Accrual capped = applyYearlyCap(rule.get(), userId, leaveType, leaveYear, period, computed);

        BigDecimal previous = existing.map(LeaveCredit::getDays).orElse(BigDecimal.ZERO);
        LeaveCredit credit = existing.orElseGet(() -> LeaveCredit.builder()
                .userId(userId).leaveType(leaveType).kind(LeaveCreditKind.MONTHLY_ACCRUAL)
                .period(period).leaveYear(leaveYear)
                .build());
        credit.setDays(capped.days());
        credit.setDaysCounted(capped.daysCounted());
        credit.setBasis(capped.basis());
        credit.setPostedAt(Instant.now());
        leaveCreditRepository.save(credit);

        leaveBalanceService.adjustQuota(userId, leaveYear, leaveType, capped.days().subtract(previous));

        log.info("leave.accrual userId={} type={} month={} credit={} previous={}",
                userId, leaveType, period, capped.days(), previous);
        return capped.days();
    }

    private Accrual compute(LeaveRule rule, Employee employee, YearMonth month,
                            MonthlyAttendanceSummary attendance) {
        if (rule.getGrantMethod() == LeaveGrant.EARNED_BY_ATTENDANCE) {
            EarnedLeaveCalculator.EarnedLeaveCredit earned = calculator.calculate(rule,
                    attendance.getWorkingDays(), attendance.getLopDays(), employedWholeMonth(employee, month));
            return new Accrual(earned.credit(), earned.daysCounted(), earned.basis());
        }
        // A monthly accrual is for a month on the books, not a month worked, so a
        // joiner's first part-month credits nothing rather than a full day.
        boolean wholeMonth = employedWholeMonth(employee, month);
        BigDecimal credit = wholeMonth
                ? rule.getMonthlyCredit().setScale(1, RoundingMode.HALF_UP)
                : zero();
        String basis = wholeMonth
                ? "a whole month on the books: %s".formatted(plain(credit))
                : "not employed the whole month: nothing credited";
        return new Accrual(credit, null, basis);
    }

    /**
     * A year accrues at most what its rule allows. The cap is on the year's
     * total, not on one month, so the month that reaches it credits the
     * remainder and the months after it credit nothing - and because the ledger
     * is keyed per period, a repost recomputes the same answer rather than
     * spending the headroom twice.
     */
    private Accrual applyYearlyCap(LeaveRule rule, String userId, LeaveType leaveType, int leaveYear,
                                   String period, Accrual computed) {
        BigDecimal cap = rule.getYearlyAccrualCap();
        if (cap == null || computed.days().signum() <= 0) {
            return computed;
        }
        BigDecimal alreadyAccrued = leaveCreditRepository
                .findAllByUserIdAndLeaveTypeAndKindAndLeaveYear(userId, leaveType,
                        LeaveCreditKind.MONTHLY_ACCRUAL, leaveYear)
                .stream()
                .filter(credit -> !period.equals(credit.getPeriod()))
                .map(LeaveCredit::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal headroom = cap.subtract(alreadyAccrued).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
        if (computed.days().compareTo(headroom) <= 0) {
            return computed;
        }
        String basis = headroom.signum() == 0
                ? "%s, but the year has already accrued its cap of %s".formatted(computed.basis(), plain(cap))
                : "%s, trimmed to %s by the yearly cap of %s".formatted(computed.basis(), plain(headroom), plain(cap));
        return new Accrual(headroom, computed.daysCounted(), basis);
    }

    private static boolean accrues(LeaveRule rule) {
        return rule.getGrantMethod() == LeaveGrant.EARNED_BY_ATTENDANCE
                || (rule.getGrantMethod() == LeaveGrant.MONTHLY_ACCRUAL && rule.getMonthlyCredit() != null);
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

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(1);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** One month's answer, before and after the yearly cap. */
    private record Accrual(BigDecimal days, BigDecimal daysCounted, String basis) {
    }
}
