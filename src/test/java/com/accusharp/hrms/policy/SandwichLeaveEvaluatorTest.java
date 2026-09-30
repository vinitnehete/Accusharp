package com.accusharp.hrms.policy;

import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.service.leave.LeaveCalculationService.LeaveDay;
import com.accusharp.hrms.service.policy.SandwichLeaveEvaluator;
import com.accusharp.hrms.service.policy.SandwichLeaveEvaluator.Charge;
import com.accusharp.hrms.service.policy.SandwichLeaveEvaluator.Day;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sandwich rule on its own, one calendar strip per case.
 *
 * <p>A strip is one letter per day, starting on the date given:
 * <pre>
 *   W worked (a full or half day)          L paid leave      A absent / unpaid leave
 *   H holiday         P holiday on paid leave                X holiday worked
 *   O weekly off      - no attendance row at all
 * </pre>
 * August 2026: the 15th (Independence Day) is a Saturday and the 16th a Sunday.
 */
class SandwichLeaveEvaluatorTest {

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final LocalDate AUG_13 = LocalDate.of(2026, 8, 13);
    private static final LocalDate AUG_14 = LocalDate.of(2026, 8, 14);
    private static final LocalDate AUG_15 = LocalDate.of(2026, 8, 15);
    private static final LocalDate AUG_16 = LocalDate.of(2026, 8, 16);
    private static final LocalDate AUG_17 = LocalDate.of(2026, 8, 17);

    // ---- the rule as described ----------------------------------------------

    @Test
    @DisplayName("leave on the 14th and 16th around the 15th: all three days are unpaid")
    void leaveOnBothSidesLosesAllThreeDays() {
        Charge charge = charge(AUG_13, "WLHLW");

        assertThat(charge.holidays()).containsExactly(AUG_15);
        assertThat(charge.leaveDates()).containsExactly(AUG_14, AUG_16);
        assertThat(charge.lopDays()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("leave on the 14th but worked the 16th: one working day is enough, nothing is lost")
    void workingOneSideKeepsTheHoliday() {
        assertThat(charge(AUG_13, "WLHWW")).isEqualTo(Charge.NONE);
        assertThat(charge(AUG_13, "WWHLW")).isEqualTo(Charge.NONE);
    }

    @Test
    @DisplayName("leave applied for the 14th, 15th and 16th together: the holiday is paid")
    void leaveThroughTheHolidayIsPaid() {
        assertThat(charge(AUG_13, "WLPLW")).isEqualTo(Charge.NONE);
    }

    @Test
    @DisplayName("absent without leave on both sides: the holiday is lost as well")
    void absentOnBothSidesLosesTheHoliday() {
        Charge charge = charge(AUG_13, "WAHAW");

        assertThat(charge.holidays()).containsExactly(AUG_15);
        // The two absences are unpaid already - only the holiday is added.
        assertThat(charge.leaveDates()).isEmpty();
        assertThat(charge.lopDays()).isEqualByComparingTo("1");
    }

    // ---- a weekly off next to the holiday -------------------------------------

    @Test
    @DisplayName("holiday then weekly off: worked on the day after the weekly off, so nothing is lost")
    void weeklyOffAfterIsLookedPast() {
        // 14 leave, 15 holiday, 16 Sunday off, 17 worked.
        assertThat(charge(AUG_13, "WLHOW")).isEqualTo(Charge.NONE);
    }

    @Test
    @DisplayName("weekly off then holiday: worked on the day before the weekly off, so nothing is lost")
    void weeklyOffBeforeIsLookedPast() {
        // 13 worked, 14 off, 15 holiday, 16 leave.
        assertThat(charge(AUG_13, "WOHLW")).isEqualTo(Charge.NONE);
    }

    @Test
    @DisplayName("leave on both sides of holiday + weekly off: the holiday and both leaves are lost, the weekly off is not")
    void weeklyOffInsideTheSandwichIsNotCharged() {
        Charge charge = charge(AUG_13, "WLHOLW");

        assertThat(charge.holidays()).containsExactly(AUG_15);
        assertThat(charge.leaveDates()).containsExactly(AUG_14, AUG_17);
        assertThat(charge.lopDays()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("public holidays only: leave around a plain weekly off is never a sandwich")
    void weeklyOffAloneNeverTriggers() {
        assertThat(charge(AUG_13, "WLOOLW")).isEqualTo(Charge.NONE);
    }

    // ---- what counts as working ----------------------------------------------

    @Test
    @DisplayName("worked on the holiday itself: nothing is lost")
    void workingTheHolidayItselfKeepsIt() {
        assertThat(charge(AUG_13, "WLXLW")).isEqualTo(Charge.NONE);
    }

    @Test
    @DisplayName("a day with no attendance row on one side is unknown, and unknown is never charged")
    void missingNeighbourIsNotCharged() {
        assertThat(charge(AUG_13, "WLH-W")).isEqualTo(Charge.NONE);
        assertThat(charge(AUG_13, "W-HLW")).isEqualTo(Charge.NONE);
        // The month after is not generated yet: nothing past the 15th.
        assertThat(charge(AUG_13, "WLH")).isEqualTo(Charge.NONE);
    }

    // ---- options and edges ------------------------------------------------------

    @Test
    @DisplayName("with 'leave around the holiday is also unpaid' switched off, only the holiday is lost")
    void keepingAdjacentLeavePaidLosesOnlyTheHoliday() {
        Charge charge = SandwichLeaveEvaluator.charge(days(AUG_13, "WLHLW"), AUG, false);

        assertThat(charge.holidays()).containsExactly(AUG_15);
        assertThat(charge.leaveDates()).isEmpty();
        assertThat(charge.lopDays()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("two holidays in a row cost both holidays and the leave around them")
    void twoHolidaysInOneSandwich() {
        Charge charge = charge(AUG_13, "WLHHLW");

        assertThat(charge.holidays()).containsExactly(AUG_15, AUG_16);
        assertThat(charge.lopDays()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("one leave day between two holidays is charged once, not once per holiday")
    void sharedLeaveDayIsChargedOnce() {
        // 14 L, 15 H, 16 L, 17 H, 18 L
        Charge charge = charge(AUG_13, "WLHLHLW");

        assertThat(charge.holidays()).containsExactly(AUG_15, AUG_17);
        assertThat(charge.leaveDates()).containsExactly(AUG_14, AUG_16, LocalDate.of(2026, 8, 18));
        assertThat(charge.lopDays()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("across a month end each day is charged in its own month")
    void eachDayIsChargedInItsOwnMonth() {
        // 31 July leave, 1 August holiday, 2 August leave.
        List<Day> strip = days(LocalDate.of(2026, 7, 30), "WLHLW");

        Charge july = SandwichLeaveEvaluator.charge(strip, YearMonth.of(2026, 7), true);
        Charge august = SandwichLeaveEvaluator.charge(strip, AUG, true);

        assertThat(july.holidays()).isEmpty();
        assertThat(july.leaveDates()).containsExactly(LocalDate.of(2026, 7, 31));
        assertThat(august.holidays()).containsExactly(LocalDate.of(2026, 8, 1));
        assertThat(august.leaveDates()).containsExactly(LocalDate.of(2026, 8, 2));
    }

    @Test
    @DisplayName("a half day of paid leave next to the holiday forfeits half a day")
    void halfDayLeaveForfeitsHalf() {
        List<Day> strip = new ArrayList<>(days(AUG_13, "WAHLW"));
        strip.set(1, new Day(AUG_14, true, false, false, new BigDecimal("0.5")));

        assertThat(SandwichLeaveEvaluator.charge(strip, AUG, true).lopDays()).isEqualByComparingTo("2.5");
    }

    // ---- reading a stored day ----------------------------------------------------

    @Test
    @DisplayName("a stored day is read as the rule needs it")
    void storedDaysAreReadCorrectly() {
        // A half day is working; so is a lone punch - evidence the person came in, never absence.
        assertThat(SandwichLeaveEvaluator.Day.of(stored(AUG_14, AttendanceStatus.HALF_DAY, false, false), null)
                .worked()).isTrue();
        assertThat(SandwichLeaveEvaluator.Day.of(stored(AUG_14, AttendanceStatus.INVALID_PUNCH, false, false), null)
                .worked()).isTrue();
        // A holiday falling on the weekly off took no working day away: it is a weekly off here.
        Day holidayOnSunday = SandwichLeaveEvaluator.Day.of(stored(AUG_16, AttendanceStatus.HOLIDAY, true, true), null);
        assertThat(holidayOnSunday.holiday()).isFalse();
        assertThat(holidayOnSunday.workingDay()).isFalse();
        // Paid leave carries its fraction; unpaid leave carries nothing.
        assertThat(SandwichLeaveEvaluator.Day.of(stored(AUG_14, AttendanceStatus.ON_LEAVE, false, false),
                new LeaveDay(LeaveType.CASUAL_LEAVE, BigDecimal.ONE, true)).paidLeave()).isEqualByComparingTo("1");
        assertThat(SandwichLeaveEvaluator.Day.of(stored(AUG_14, AttendanceStatus.ON_LEAVE, false, false),
                new LeaveDay(LeaveType.LEAVE_WITHOUT_PAY, BigDecimal.ONE, false)).paidLeave()).isEqualByComparingTo("0");
    }

    // ---- helpers -------------------------------------------------------------------

    private static Charge charge(LocalDate start, String strip) {
        return SandwichLeaveEvaluator.charge(days(start, strip), AUG, true);
    }

    private static List<Day> days(LocalDate start, String strip) {
        List<Day> days = new ArrayList<>();
        for (int i = 0; i < strip.length(); i++) {
            LocalDate date = start.plusDays(i);
            switch (strip.charAt(i)) {
                case 'W' -> days.add(new Day(date, true, false, true, BigDecimal.ZERO));
                case 'L' -> days.add(new Day(date, true, false, false, BigDecimal.ONE));
                case 'A' -> days.add(new Day(date, true, false, false, BigDecimal.ZERO));
                case 'H' -> days.add(new Day(date, false, true, false, BigDecimal.ZERO));
                case 'P' -> days.add(new Day(date, false, true, false, BigDecimal.ONE));
                case 'X' -> days.add(new Day(date, false, true, true, BigDecimal.ZERO));
                case 'O' -> days.add(new Day(date, false, false, false, BigDecimal.ZERO));
                case '-' -> { }
                default -> throw new IllegalArgumentException("unknown day code " + strip.charAt(i));
            }
        }
        return days;
    }

    private static DailyAttendance stored(LocalDate date, AttendanceStatus status, boolean holiday, boolean weekOff) {
        return DailyAttendance.builder().userId("SE1").attendanceDate(date).status(status)
                .holiday(holiday).weekOff(weekOff).invalidPunch(status == AttendanceStatus.INVALID_PUNCH).build();
    }
}
