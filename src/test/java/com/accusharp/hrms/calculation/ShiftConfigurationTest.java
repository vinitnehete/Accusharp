package com.accusharp.hrms.calculation;

import com.accusharp.hrms.dto.ShiftResponse;
import com.accusharp.hrms.entity.Shift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shift master decides how every punch is read, so a wrong row here is the
 * most expensive kind of wrong. These pin the two misconfigurations that have
 * actually happened in production data.
 */
class ShiftConfigurationTest {

    @Test
    @DisplayName("a night shift is one whose end time is not after its start time")
    void crossingMidnightIsDerivedFromTheTimes() {
        assertThat(shift(LocalTime.of(19, 0), LocalTime.of(7, 0), 8, 0, 180).crossesMidnight())
                .isTrue();
        assertThat(shift(LocalTime.of(6, 0), LocalTime.of(19, 0), 8, 0, 180).crossesMidnight())
                .isFalse();
    }

    @Test
    @DisplayName("a shift called NIGHT but stored as a day shift is reported as not crossing midnight")
    void aMisnamedNightShiftIsVisible() {
        // The real incident: NIGHT was saved as 06:00-19:00, a copy of MORNING.
        // Nothing in the stored row says it is wrong - only the derived flag does.
        Shift misconfigured = shift(LocalTime.of(6, 0), LocalTime.of(19, 0), 8, 0, 180);
        misconfigured.setShiftCode("NIGHT");

        ShiftResponse response = ShiftResponse.of(misconfigured);

        assertThat(response.crossesMidnight()).isFalse();
        assertThat(response.spanHours()).isEqualByComparingTo("13.00");
    }

    @Test
    @DisplayName("a zero overtime window is flagged - it discards every late exit punch")
    void zeroOvertimeWindowIsFlagged() {
        ShiftResponse response = ShiftResponse.of(
                shift(LocalTime.of(9, 0), LocalTime.of(18, 0), 8, 60, 0));

        assertThat(response.warnings())
                .anySatisfy(w -> assertThat(w).contains("overtimeWindowMinutes is 0"));
    }

    @Test
    @DisplayName("paid hours far shorter than the shift span are flagged as phantom overtime")
    void phantomOvertimeIsFlagged() {
        // 13h span, 8h paid, no break - books ~5h of overtime every single day.
        ShiftResponse response = ShiftResponse.of(
                shift(LocalTime.of(6, 0), LocalTime.of(19, 0), 8, 0, 180));

        assertThat(response.warnings())
                .anySatisfy(w -> assertThat(w).contains("overtime every day"));
    }

    @Test
    @DisplayName("a sanely configured shift raises nothing")
    void aSaneShiftIsQuiet() {
        // 9h span, 8h paid plus a 1h break, 4h overtime window.
        ShiftResponse response = ShiftResponse.of(
                shift(LocalTime.of(6, 0), LocalTime.of(15, 0), 8, 60, 240));

        assertThat(response.warnings()).isEmpty();
        assertThat(response.spanHours()).isEqualByComparingTo("9.00");
    }

    private Shift shift(LocalTime start, LocalTime end, int workingHours, int breakMinutes, int otw) {
        return Shift.builder()
                .shiftCode("TEST").shiftName("Test")
                .startTime(start).endTime(end)
                .workingHours(workingHours).breakMinutes(breakMinutes)
                .graceMinutes(15).overtimeWindowMinutes(otw)
                .build();
    }
}
