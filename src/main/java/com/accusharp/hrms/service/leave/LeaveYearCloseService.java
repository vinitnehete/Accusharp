package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.dto.LeaveYearCloseRow;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.entity.LeaveCredit;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.ExcessHandling;
import com.accusharp.hrms.enums.LeaveCreditKind;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Closes a leave year: carries each balance into the next year up to its rule's
 * cap, and either lists what is over the cap for payout or lets it lapse, as the
 * rule says.
 *
 * <p>Each company closes on its own year end - 31 December, or 31 March for a
 * company on the financial year (see {@link LeaveYears}) - and the rules
 * consulted are the ones in effect on that day.
 *
 * <p>An explicit action rather than an automatic rollover: the last month's
 * earned leave is credited when that month's payroll runs, which is after the
 * year has ended. HR closes the year once that payroll is done.
 *
 * <p>Safe to run again: each carry-forward is one ledger row per employee, leave
 * type and year, replaced on a rerun with the balance moved by the difference.
 *
 * <p>Leave with no cap configured lapses, which is what every balance did before
 * this existed. A payout is reported, not paid: paying it is a payroll change of
 * its own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveYearCloseService {

    private final EmployeeService employeeService;
    private final LeaveRuleResolver leaveRuleResolver;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveCreditRepository leaveCreditRepository;
    private final AuditService auditService;

    @Transactional
    public List<LeaveYearCloseRow> closeYear(int year, String closedBy) {
        String period = String.valueOf(year);
        List<LeaveYearCloseRow> rows = new ArrayList<>();

        for (Employee employee : employeeService.getActiveEntities()) {
            String userId = employee.getUserId();
            int startMonth = LeaveYears.startMonthOf(employee);
            LocalDate yearEnd = LeaveYears.endOf(year, startMonth);

            for (LeaveType type : LeaveType.values()) {
                if (!type.isPaid()) {
                    continue;
                }
                Optional<LeaveRule> rule = leaveRuleResolver.ruleFor(employee, type, yearEnd);
                BigDecimal cap = rule.map(LeaveRule::getCarryForwardCap).orElse(null);
                boolean lapse = rule.map(LeaveRule::getExcessOverCap).orElse(null) == ExcessHandling.LAPSE;
                Optional<LeaveCredit> existing = leaveCreditRepository.findByUserIdAndLeaveTypeAndKindAndPeriod(
                        userId, type, LeaveCreditKind.CARRY_FORWARD, period);
                if (cap == null && existing.isEmpty()) {
                    continue;
                }

                BigDecimal available = leaveBalanceRepository.findByUserIdAndLeaveYearAndLeaveType(userId, year, type)
                        .map(LeaveBalance::available).orElse(BigDecimal.ZERO);
                BigDecimal carried = cap == null ? BigDecimal.ZERO : available.max(BigDecimal.ZERO).min(cap);
                BigDecimal over = cap == null ? BigDecimal.ZERO : available.subtract(cap).max(BigDecimal.ZERO);
                BigDecimal toPayOut = lapse ? BigDecimal.ZERO : over;
                BigDecimal lapsed = lapse ? over : BigDecimal.ZERO;

                BigDecimal previous = existing.map(LeaveCredit::getDays).orElse(BigDecimal.ZERO);
                LeaveCredit credit = existing.orElseGet(() -> LeaveCredit.builder()
                        .userId(userId).leaveType(type).kind(LeaveCreditKind.CARRY_FORWARD)
                        .period(period).leaveYear(year + 1)
                        .build());
                credit.setDays(carried.setScale(1));
                credit.setExcessDays(toPayOut.setScale(1));
                credit.setLapsedDays(lapsed.setScale(1));
                credit.setBasis("carried from %s: %s available, cap %s - %s carried, %s over the cap %s"
                        .formatted(LeaveYears.label(year, startMonth), plain(available),
                                cap == null ? "none" : plain(cap), plain(carried), plain(over),
                                lapse ? "lapsed" : "to pay out"));
                credit.setPostedAt(Instant.now());
                credit.setPostedBy(closedBy);
                leaveCreditRepository.save(credit);

                leaveBalanceService.adjustQuota(userId, year + 1, type, carried.subtract(previous));
                rows.add(new LeaveYearCloseRow(userId, employee.getEmployeeName(), type,
                        available.setScale(1), carried.setScale(1), toPayOut.setScale(1), lapsed.setScale(1)));
            }
        }

        log.info("leave.year-close year={} rows={} by={}", year, rows.size(), closedBy);
        auditService.record("LEAVE_YEAR_CLOSE", "LeaveBalance", period, AuditOutcome.SUCCESS,
                "rows=" + rows.size());
        return rows;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
