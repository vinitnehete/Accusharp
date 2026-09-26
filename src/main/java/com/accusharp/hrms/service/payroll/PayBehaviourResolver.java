package com.accusharp.hrms.service.payroll;

import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.EmploymentType;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.OvertimeBasis;
import com.accusharp.hrms.enums.PayBasis;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out how one employee is paid: from their {@link EmploymentType} row if
 * they have one, and otherwise from the legacy {@link EmployeeStatus} enum,
 * reproducing today's behaviour exactly.
 *
 * <h2>The fallback is the whole point</h2>
 *
 * <p>An employee with no {@code employmentType} resolves to precisely the
 * behaviour {@code PayrollService} hardcoded before this class existed:
 *
 * <table border="1">
 *   <caption>Legacy {@link EmployeeStatus} semantics, as data</caption>
 *   <tr><th>status</th><th>payBasis</th><th>cap</th><th>lopApplies</th>
 *       <th>paidLeaveAddsPayableDays</th><th>overtimeBasis</th><th>paidLeaveEarnsOvertime</th></tr>
 *   <tr><td>DAY_WISE</td><td>PER_ATTENDED_DAY</td><td>salaryRule.dayWiseDaysInMonth</td><td>false</td>
 *       <td>false</td><td>MONTHLY_TOTAL_HOURS</td><td>true</td></tr>
 *   <tr><td>PERMANENT<br>CONTRACT<br>INTERN</td><td>PER_CALENDAR_DAY_LESS_LOP</td><td>-</td><td>true</td>
 *       <td>true</td><td>PER_DAY_SHIFT_EXCESS</td><td>false</td></tr>
 * </table>
 *
 * <p>So a database with no {@code employment_type} rows at all pays everybody
 * exactly as it did before, and adopting the table is opt-in per employee
 * rather than a migration everyone must complete before the next payroll run.
 * That property is what makes this change safe to deploy to a running client,
 * and it is asserted by {@code PayBehaviourResolverTest} plus every pre-existing
 * payroll test, all of which use employees with no employment type.
 *
 * <p>{@code payableDaysCap} is resolved here rather than read raw: a type that
 * leaves it null inherits the company's {@code salaryRule.dayWiseDaysInMonth},
 * which is where that number lives today. Setting it on the type overrides it
 * for that type alone - which is what lets two day-wise populations differ, the
 * thing a single company-wide figure cannot express.
 */
@Service
@RequiredArgsConstructor
public class PayBehaviourResolver {

    /**
     * @param rule the employee's own company's salary rule - supplies the
     *             day-wise cap wherever the employment type does not override it
     */
    @Transactional(readOnly = true)
    public PayBehaviour resolve(Employee employee, SalaryRule rule) {
        EmploymentType type = employee.getEmploymentType();
        return type == null ? legacy(employee.getStatus(), rule) : configured(type, rule);
    }

    private PayBehaviour configured(EmploymentType type, SalaryRule rule) {
        Integer cap = type.getPayableDaysCap() != null
                ? type.getPayableDaysCap()
                : (type.getPayBasis() == PayBasis.PER_ATTENDED_DAY ? rule.getDayWiseDaysInMonth() : null);

        return new PayBehaviour(type.getTypeCode(), type.getPayBasis(), cap,
                type.isLopApplies(), type.isPaidLeaveAddsPayableDays(),
                type.getOvertimeBasis(), type.isPaidLeaveEarnsOvertime(),
                type.isSegmentedRevisionEarnings());
    }

    /**
     * Today's hardcoded semantics, expressed as data. Deliberately written out
     * rather than derived from {@code isPaidPerAttendedDay()} alone: the whole
     * problem being fixed is that seven distinct behaviours were being inferred
     * from one boolean, and listing them separately is what makes each one
     * visible and changeable.
     */
    private PayBehaviour legacy(EmployeeStatus status, SalaryRule rule) {
        boolean dayWise = status != null && status.isPaidPerAttendedDay();

        return dayWise
                ? new PayBehaviour(status.name(), PayBasis.PER_ATTENDED_DAY,
                        rule.getDayWiseDaysInMonth(),
                        false,  // attendance IS the pay - no LOP concept
                        false,  // paid leave earns no share of the fixed structure...
                        OvertimeBasis.MONTHLY_TOTAL_HOURS,
                        true,   // ...but does earn its own overtime hours
                        false)  // no calendar-month proration to segment
                : new PayBehaviour(status == null ? EmployeeStatus.PERMANENT.name() : status.name(),
                        PayBasis.PER_CALENDAR_DAY_LESS_LOP,
                        null,   // prorated against the month's real length
                        true,
                        true,
                        OvertimeBasis.PER_DAY_SHIFT_EXCESS,
                        false,
                        true);
    }

    /** The default behaviour for a seeded type, matching what that legacy status does today. */
    public static EmploymentType seedFor(EmployeeStatus status) {
        boolean dayWise = status.isPaidPerAttendedDay();
        return EmploymentType.builder()
                .typeCode(status.name())
                .typeName(displayName(status))
                .payBasis(dayWise ? PayBasis.PER_ATTENDED_DAY : PayBasis.PER_CALENDAR_DAY_LESS_LOP)
                // Null, not 26: the seeded type inherits the company's own
                // dayWiseDaysInMonth rather than freezing a copy of it that
                // would then silently stop tracking the salary rule.
                .payableDaysCap(null)
                .lopApplies(!dayWise)
                .paidLeaveAddsPayableDays(!dayWise)
                .overtimeBasis(dayWise ? OvertimeBasis.MONTHLY_TOTAL_HOURS : OvertimeBasis.PER_DAY_SHIFT_EXCESS)
                .paidLeaveEarnsOvertime(dayWise)
                .segmentedRevisionEarnings(!dayWise)
                .autoRosterDefaultShift(status == EmployeeStatus.PERMANENT)
                .active(true)
                .build();
    }

    private static String displayName(EmployeeStatus status) {
        String lower = status.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
