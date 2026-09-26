package com.accusharp.hrms.weekoff;

import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.shift.LegacySystemRosterCleanup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clearing out what the deleted cron job left behind.
 *
 * <p>Every client database holds roster rows the monthly generator wrote,
 * stamped {@code SYSTEM_DEFAULT_ROSTER} and running up to two months into the
 * future. They all say the same thing - GENERAL, Sunday off - because that is
 * all the generator could say. Left in place they would keep overriding each
 * employee's real configured weekly off (explicit rows win, and these look
 * explicit) until they ran out, so the fix would appear not to work for two
 * months and then start working on its own.
 *
 * <p><b>Future-dated only.</b> Past rows are left exactly where they are.
 * Regenerating an already-reviewed month has to reproduce what the roster
 * actually said at the time; rewriting history to match today's configuration
 * would move loss of pay somebody has already been paid against.
 */
@SpringBootTest
class LegacySystemRosterCleanupTest {

    private static final String LEGACY = "SYSTEM_DEFAULT_ROSTER";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);

    @Autowired private LegacySystemRosterCleanup cleanup;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private ShiftRepository shiftRepository;

    private Shift general;

    @BeforeEach
    void setUp() {
        shiftScheduleRepository.deleteAll();
        shiftRepository.deleteAll();
        general = shiftRepository.save(Shift.builder()
                .shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(15).overtimeWindowMinutes(240)
                .build());
    }

    @Test
    @DisplayName("future-dated rows the cron generated are removed")
    void removesFutureGeneratedRows() {
        row("CL001", TODAY.plusDays(1), LEGACY);
        row("CL001", TODAY.plusDays(40), LEGACY);

        assertThat(cleanup.removeFutureDated(TODAY)).isEqualTo(2);
        assertThat(shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc("CL001")).isEmpty();
    }

    @Test
    @DisplayName("today counts as future - it has not been generated against yet")
    void todayIsRemovedToo() {
        row("CL002", TODAY, LEGACY);

        assertThat(cleanup.removeFutureDated(TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("past rows are left alone so an old month regenerates as it was")
    void keepsPastRows() {
        row("CL003", TODAY.minusDays(1), LEGACY);
        row("CL003", TODAY.minusMonths(3), LEGACY);

        assertThat(cleanup.removeFutureDated(TODAY)).isZero();
        assertThat(shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc("CL003")).hasSize(2);
    }

    @Test
    @DisplayName("a future row somebody actually assigned is never touched")
    void keepsHandAssignedFutureRows() {
        row("CL004", TODAY.plusDays(5), "HR001");
        row("CL004", TODAY.plusDays(6), null);

        // Only rows carrying the generator's own stamp are the generator's to
        // remove. A night shift HR rostered for next week is real roster data.
        assertThat(cleanup.removeFutureDated(TODAY)).isZero();
        assertThat(shiftScheduleRepository.findAllByUserIdOrderByShiftDateAsc("CL004")).hasSize(2);
    }

    @Test
    @DisplayName("running it twice removes nothing the second time")
    void isIdempotent() {
        row("CL005", TODAY.plusDays(3), LEGACY);

        assertThat(cleanup.removeFutureDated(TODAY)).isEqualTo(1);
        // It runs at every startup. Nothing writes these rows any more, so the
        // second run and every one after it is a no-op.
        assertThat(cleanup.removeFutureDated(TODAY)).isZero();
    }

    private void row(String userId, LocalDate date, String assignedBy) {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(userId).shiftDate(date).shift(general)
                .weekOff(date.getDayOfWeek().getValue() == 7).assignedBy(assignedBy).build());
    }
}
