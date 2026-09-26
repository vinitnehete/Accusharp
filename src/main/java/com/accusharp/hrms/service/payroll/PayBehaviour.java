package com.accusharp.hrms.service.payroll;

import com.accusharp.hrms.enums.OvertimeBasis;
import com.accusharp.hrms.enums.PayBasis;

/**
 * Everything an employment type says about how somebody is paid, resolved into
 * one value {@code PayrollService} reads instead of branching on an enum.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Payroll used to open with one line:
 *
 * <pre>
 *   boolean dayWise = employee.getStatus().isPaidPerAttendedDay();
 * </pre>
 *
 * and then branch on it <b>seven times</b> - the proration base, whether LOP
 * applies, how payable days derive, whether paid leave counts toward them, the
 * stored present-days figure, which overtime formula runs, and whether a
 * mid-month salary revision is segmented. Seven payroll behaviours hanging off
 * a hardcoded Java enum, which meant a company could change the <em>numbers</em>
 * (the 26, the 8 hours) but never the behaviour, and could not add an
 * employment type at all.
 *
 * <p>Each field below is one of those seven branches, turned into data. The
 * arithmetic is unchanged - the {@code if} simply moved from the enum to a row.
 *
 * @param typeCode                   what the employee's type is called, for the payroll snapshot
 * @param payBasis                   the proration model
 * @param payableDaysCap             the fixed monthly base for {@link PayBasis#PER_ATTENDED_DAY},
 *                                   already resolved (from the type's own value, or the company's
 *                                   {@code salaryRule.dayWiseDaysInMonth} where the type does not
 *                                   override it). Null for calendar-day types, which prorate
 *                                   against the month's real length.
 * @param lopApplies                 false means attendance shortfalls never become loss of pay -
 *                                   correct for per-attended-day types, where being absent simply
 *                                   means not being paid for that day
 * @param paidLeaveAddsPayableDays   whether approved paid leave earns a share of the fixed salary
 *                                   structure on top of attended days
 * @param overtimeBasis              which overtime figure is paid
 * @param paidLeaveEarnsOvertime     whether approved paid leave adds its own hours to overtime,
 *                                   uncapped and additive
 * @param segmentedRevisionEarnings  whether a mid-period salary revision splits the gross-derived
 *                                   earnings across the two rates
 */
public record PayBehaviour(String typeCode,
                           PayBasis payBasis,
                           Integer payableDaysCap,
                           boolean lopApplies,
                           boolean paidLeaveAddsPayableDays,
                           OvertimeBasis overtimeBasis,
                           boolean paidLeaveEarnsOvertime,
                           boolean segmentedRevisionEarnings) {

    public boolean isPaidPerAttendedDay() {
        return payBasis.isPaidPerAttendedDay();
    }

    public boolean usesMonthlyOvertime() {
        return overtimeBasis == OvertimeBasis.MONTHLY_TOTAL_HOURS;
    }
}
