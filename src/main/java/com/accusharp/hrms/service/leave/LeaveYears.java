package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.entity.Employee;

import java.time.LocalDate;

/**
 * Leave-year arithmetic for a company whose leave year starts in January (the
 * calendar year) or April (the Indian financial year).
 *
 * <p>A leave year is numbered by the year it <b>starts</b> in. That keeps
 * {@code leave_balance.leave_year} an integer and changes nothing at all for a
 * calendar company; for an April company, leave year 2026 is April 2026 to
 * March 2027, shown as {@code 2026-27}.
 */
public final class LeaveYears {

    /** January: the calendar year, and every company's default. */
    public static final int CALENDAR = 1;

    private LeaveYears() {
    }

    public static int leaveYearOf(LocalDate date, int startMonth) {
        return date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
    }

    public static LocalDate startOf(int leaveYear, int startMonth) {
        return LocalDate.of(leaveYear, startMonth, 1);
    }

    public static LocalDate endOf(int leaveYear, int startMonth) {
        return startOf(leaveYear, startMonth).plusYears(1).minusDays(1);
    }

    /** {@code 2026} for a calendar year, {@code 2026-27} for a financial one. */
    public static String label(int leaveYear, int startMonth) {
        return startMonth == CALENDAR
                ? String.valueOf(leaveYear)
                : "%d-%02d".formatted(leaveYear, (leaveYear + 1) % 100);
    }

    /** Months left in the leave year from a joining date, the joining month included. */
    public static int monthsLeftFrom(LocalDate joined, int startMonth) {
        return 12 - Math.floorMod(joined.getMonthValue() - startMonth, 12);
    }

    /** The employee's company's leave-year start; the calendar year when there is no company. */
    public static int startMonthOf(Employee employee) {
        if (employee == null || employee.getCompany() == null) {
            return CALENDAR;
        }
        int month = employee.getCompany().getLeaveYearStartMonth();
        return month >= 1 && month <= 12 ? month : CALENDAR;
    }
}
