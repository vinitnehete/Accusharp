package com.accusharp.hrms.calculation;

import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.service.calculation.AttendanceCalculationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceCalculationServiceTest {

    private final AttendanceCalculationService service = new AttendanceCalculationService();

    private static final LocalDate DAY = LocalDate.of(2026, 8, 3);

    private Shift morning() {
        return Shift.builder().shiftCode("MORNING").shiftName("Morning")
                .startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240).build();
    }

    private Shift night() {
        return Shift.builder().shiftCode("NIGHT").shiftName("Night")
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(8, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240).build();
    }

    private DeviceLog punch(LocalDateTime at) {
        return DeviceLog.builder().deviceLogId(at.getNano() + at.getHour() * 1000L)
                .userId("EMP001").logDate(at).build();
    }

    @Test
    @DisplayName("a night shift's window runs into the next calendar day")
    void nightShiftWindowCrossesMidnight() {
        Shift night = night();

        assertThat(night.crossesMidnight()).isTrue();
        assertThat(service.windowEnd(DAY, night).toLocalDate()).isEqualTo(DAY.plusDays(1));
        assertThat(service.scheduledEnd(DAY, night)).isEqualTo(DAY.plusDays(1).atTime(8, 0));
    }

    @Test
    @DisplayName("a night shift worked past midnight still belongs to its start date")
    void nightShiftAttendanceBelongsToStartDate() {
        List<DeviceLog> punches = List.of(
                punch(DAY.atTime(17, 55)),
                punch(DAY.plusDays(1).atTime(2, 55)));

        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, night(), punches,
                false, false, false);

        assertThat(day.attendanceDate()).isEqualTo(DAY);
        assertThat(day.status()).isEqualTo(AttendanceStatus.PRESENT);
        // 9h span less the 60 minute unpaid break.
        assertThat(day.workingHours()).isEqualByComparingTo("8.00");
        assertThat(day.overtimeHours()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an exit hours after the shift is overtime, not a missing punch")
    void lateExitInsideOvertimeWindowIsCounted() {
        // Scheduled out is 15:00; she leaves at 18:30, well past any entry-side
        // buffer. This must read as overtime, never as a single-punch error.
        List<DeviceLog> punches = List.of(
                punch(DAY.atTime(6, 0)),
                punch(DAY.atTime(18, 30)));

        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, morning(), punches,
                false, false, false);

        assertThat(day.invalidPunch()).isFalse();
        assertThat(day.status()).isEqualTo(AttendanceStatus.PRESENT);
        // 12.5h span - 1h break = 11.5h worked, 3.5h beyond the 8h shift.
        assertThat(day.workingHours()).isEqualByComparingTo("11.50");
        assertThat(day.overtimeHours()).isEqualByComparingTo("3.50");
        assertThat(day.earlyExitMinutes()).isZero();
    }

    @Test
    @DisplayName("the overtime window bounds how late a punch can still count")
    void punchBeyondTheOvertimeWindowIsExcluded() {
        Shift shift = morning();

        assertThat(service.windowEnd(DAY, shift)).isEqualTo(DAY.atTime(19, 0));

        shift.setOvertimeWindowMinutes(60);
        assertThat(service.windowEnd(DAY, shift)).isEqualTo(DAY.atTime(16, 0));
    }

    @Test
    @DisplayName("hours beyond the shift length become overtime")
    void countsOvertime() {
        List<DeviceLog> punches = List.of(
                punch(DAY.atTime(6, 0)),
                punch(DAY.atTime(17, 0)));

        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, morning(), punches,
                false, false, false);

        // 11h span - 1h break = 10h worked, 2h over the 8h shift.
        assertThat(day.workingHours()).isEqualByComparingTo("10.00");
        assertThat(day.overtimeHours()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("arriving after the grace period is late; leaving early is flagged")
    void detectsLateEntryAndEarlyExit() {
        List<DeviceLog> punches = List.of(
                punch(DAY.atTime(6, 45)),
                punch(DAY.atTime(14, 0)));

        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, morning(), punches,
                false, false, false);

        assertThat(day.lateMinutes()).isEqualTo(30);
        assertThat(day.earlyExitMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("arriving inside the grace period is not late")
    void gracePeriodIsNotLate() {
        List<DeviceLog> punches = List.of(
                punch(DAY.atTime(6, 10)),
                punch(DAY.atTime(15, 0)));

        assertThat(service.calculateDay("EMP001", DAY, morning(), punches, false, false, false)
                .lateMinutes()).isZero();
    }

    @Test
    @DisplayName("a single punch is a device error, not an absence")
    void singlePunchIsInvalid() {
        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, morning(),
                List.of(punch(DAY.atTime(6, 0))), false, false, false);

        assertThat(day.invalidPunch()).isTrue();
        assertThat(day.status()).isEqualTo(AttendanceStatus.INVALID_PUNCH);
    }

    @Test
    @DisplayName("no punches on an expected working day is an absence")
    void noPunchesIsAbsent() {
        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, morning(), List.of(),
                false, false, false);

        assertThat(day.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(day.invalidPunch()).isFalse();
    }

    @Test
    @DisplayName("no punches while on approved leave reads as leave, not absence")
    void noPunchesOnLeaveIsOnLeave() {
        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, morning(), List.of(),
                false, false, true);

        assertThat(day.status()).isEqualTo(AttendanceStatus.ON_LEAVE);
    }

    @Test
    @DisplayName("working under half the shift earns a half day")
    void shortDayIsHalfDay() {
        List<DeviceLog> punches = List.of(
                punch(DAY.atTime(6, 0)),
                punch(DAY.atTime(11, 0)));

        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, morning(), punches,
                false, false, false);

        // 5h span - 1h break = 4h, which is half of the 8h shift.
        assertThat(day.status()).isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(service.dayFraction(day.status())).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("four punches measure the real break instead of the configured one")
    void fourPunchesUseActualBreak() {
        List<DeviceLog> punches = List.of(
                punch(DAY.atTime(6, 0)),
                punch(DAY.atTime(10, 0)),
                punch(DAY.atTime(10, 30)),
                punch(DAY.atTime(15, 0)));

        DailyAttendanceResponse day = service.calculateDay("EMP001", DAY, morning(), punches,
                false, false, false);

        // 9h span - the 30 minutes actually spent out.
        assertThat(day.breakHours()).isEqualByComparingTo("0.50");
        assertThat(day.workingHours()).isEqualByComparingTo("8.50");
    }
}
