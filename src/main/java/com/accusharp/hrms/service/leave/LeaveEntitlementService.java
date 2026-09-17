package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.enums.LeaveType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * What a year's leave balance opens at, for one employee and one leave type.
 *
 * <p>Only ever consulted when a balance row is first created. A row that already
 * exists - including one typed into the table by hand, which is how opening EL
 * balances arrive - is never re-seeded, so configuring a rule can never wipe a
 * figure somebody entered.
 */
@Service
@RequiredArgsConstructor
public class LeaveEntitlementService {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final LeaveRuleResolver leaveRuleResolver;

    /**
     * <ul>
     *   <li>No rule: the {@link LeaveType} default - CL 12, SL 8, EL 0 - exactly
     *       what every balance opened at before rules existed.</li>
     *   <li>{@code YEARLY_GRANT}: the rule's days, pro-rated for someone joining
     *       during the year to the months left (joining month included), to the
     *       nearest half day.</li>
     *   <li>{@code EARNED_BY_ATTENDANCE}: zero - it is credited month by month.</li>
     *   <li>{@code NOT_ENTITLED}: zero.</li>
     * </ul>
     *
     * <p>A contractor's worker opens at zero whatever the rules say: their leave
     * is their contractor's statutory obligation, and they are outside this
     * company's payroll.
     */
    @Transactional(readOnly = true)
    public BigDecimal openingQuota(Employee employee, int year, LeaveType leaveType) {
        BigDecimal fallback = BigDecimal.valueOf(leaveType.getDefaultYearlyQuota()).setScale(1);
        if (!leaveType.isPaid() || employee == null) {
            return fallback;
        }
        if (employee.isContractorWorker()) {
            return zero();
        }

        int startMonth = LeaveYears.startMonthOf(employee);
        LocalDate yearStart = LeaveYears.startOf(year, startMonth);
        LocalDate joined = employee.getJoiningDate();
        boolean joinedThisYear = joined != null && !joined.isBefore(yearStart)
                && !joined.isAfter(LeaveYears.endOf(year, startMonth));
        // Resolved on the day the year's entitlement began for this employee, so
        // a rule effective from next year cannot reach back into this one.
        LocalDate asOf = joinedThisYear ? joined : yearStart;

        Optional<LeaveRule> rule = leaveRuleResolver.ruleFor(employee, leaveType, asOf);
        if (rule.isEmpty()) {
            return fallback;
        }
        return switch (rule.get().getGrantMethod()) {
            case NOT_ENTITLED, EARNED_BY_ATTENDANCE -> zero();
            case YEARLY_GRANT -> {
                BigDecimal yearly = rule.get().getYearlyDays() == null ? BigDecimal.ZERO : rule.get().getYearlyDays();
                yield joinedThisYear ? proRated(yearly, joined, startMonth) : yearly.setScale(1, RoundingMode.HALF_UP);
            }
        };
    }

    /** The months left in the company's leave year, joining month included, to the nearest half day. */
    private BigDecimal proRated(BigDecimal yearly, LocalDate joined, int startMonth) {
        int monthsLeft = LeaveYears.monthsLeftFrom(joined, startMonth);
        BigDecimal exact = yearly.multiply(BigDecimal.valueOf(monthsLeft))
                .divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
        return exact.multiply(TWO).setScale(0, RoundingMode.HALF_UP)
                .divide(TWO, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(1);
    }
}
