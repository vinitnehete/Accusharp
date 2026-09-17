package com.accusharp.hrms.leave;

import com.accusharp.hrms.service.leave.LeaveYears;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which leave year a date belongs to, for a company whose leave year starts in
 * January (calendar) or April (the Indian financial year).
 *
 * <p>A leave year is numbered by the year it <b>starts</b> in, so balances keep
 * their existing integer {@code leave_year}: for a calendar company nothing
 * changes at all, and for an April company "2026" is April 2026 to March 2027,
 * shown as {@code 2026-27}.
 */
class LeaveYearsTest {

    private static final int JANUARY = 1;
    private static final int APRIL = 4;

    @Test
    @DisplayName("for a calendar company, the leave year is simply the date's year")
    void calendarYear() {
        assertThat(LeaveYears.leaveYearOf(LocalDate.of(2026, 1, 1), JANUARY)).isEqualTo(2026);
        assertThat(LeaveYears.leaveYearOf(LocalDate.of(2026, 12, 31), JANUARY)).isEqualTo(2026);
    }

    @Test
    @DisplayName("for an April company, January to March belong to the year that started last April")
    void financialYear() {
        assertThat(LeaveYears.leaveYearOf(LocalDate.of(2026, 4, 1), APRIL)).isEqualTo(2026);
        assertThat(LeaveYears.leaveYearOf(LocalDate.of(2027, 3, 31), APRIL)).isEqualTo(2026);
        assertThat(LeaveYears.leaveYearOf(LocalDate.of(2027, 4, 1), APRIL)).isEqualTo(2027);
        assertThat(LeaveYears.leaveYearOf(LocalDate.of(2026, 1, 15), APRIL)).isEqualTo(2025);
    }

    @Test
    @DisplayName("a leave year's first and last day")
    void boundaries() {
        assertThat(LeaveYears.startOf(2026, APRIL)).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(LeaveYears.endOf(2026, APRIL)).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(LeaveYears.startOf(2026, JANUARY)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(LeaveYears.endOf(2026, JANUARY)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("a financial year reads 2026-27; a calendar year reads 2026")
    void labels() {
        assertThat(LeaveYears.label(2026, APRIL)).isEqualTo("2026-27");
        assertThat(LeaveYears.label(2026, JANUARY)).isEqualTo("2026");
        assertThat(LeaveYears.label(2099, APRIL)).isEqualTo("2099-00");
    }

    @Test
    @DisplayName("months left in the leave year from a joining date, joining month included")
    void monthsLeft() {
        assertThat(LeaveYears.monthsLeftFrom(LocalDate.of(2026, 6, 10), APRIL)).isEqualTo(10);
        assertThat(LeaveYears.monthsLeftFrom(LocalDate.of(2027, 3, 1), APRIL)).isEqualTo(1);
        assertThat(LeaveYears.monthsLeftFrom(LocalDate.of(2026, 4, 1), APRIL)).isEqualTo(12);
        assertThat(LeaveYears.monthsLeftFrom(LocalDate.of(2026, 9, 1), JANUARY)).isEqualTo(4);
        assertThat(LeaveYears.monthsLeftFrom(LocalDate.of(2026, 1, 20), JANUARY)).isEqualTo(12);
    }
}
