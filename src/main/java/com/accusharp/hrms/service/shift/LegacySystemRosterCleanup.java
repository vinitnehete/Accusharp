package com.accusharp.hrms.service.shift;

import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Clears out the roster rows the deleted monthly cron job left behind.
 *
 * <p>{@code DefaultRosterService} wrote roughly ninety rows per permanent
 * employee per quarter, stamped {@code SYSTEM_DEFAULT_ROSTER}, extending two
 * months past whenever it last ran. Every one of them says the same thing -
 * GENERAL, Sunday off - because a hardcoded constant was all it could say.
 *
 * <p>They cannot simply be left. {@link DefaultRosterResolver} treats a stored
 * row as an explicit assignment and hands it back untouched, which is exactly
 * right for a row somebody chose - and exactly wrong for these, which nobody
 * chose. An employee whose real weekly off is Tuesday would keep getting Sunday
 * until the leftovers ran out, so the fix would look broken for two months and
 * then start working by itself.
 *
 * <h2>Future-dated only</h2>
 *
 * <p>Rows before today stay. Attendance for a past month has already been
 * generated, reviewed and in all likelihood paid, and regenerating it must
 * reproduce what the roster said at the time rather than what the employee's
 * configuration says now. Deleting those rows would silently re-cut historical
 * loss of pay against today's answer - the one thing this change must not do.
 *
 * <p>Runs at startup, and is idempotent because nothing writes these rows any
 * more: the first run after deployment removes them and every run after that
 * finds none. It is deliberately not a migration script - there is no Flyway
 * here ({@code ddl-auto=update}), and a step an operator has to remember is a
 * step that gets skipped on the second environment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegacySystemRosterCleanup implements ApplicationRunner {

    /** The stamp {@code DefaultRosterService} put on every row it wrote. */
    static final String LEGACY_ASSIGNED_BY = "SYSTEM_DEFAULT_ROSTER";

    private final ShiftScheduleRepository shiftScheduleRepository;

    @Override
    public void run(ApplicationArguments args) {
        int removed = removeFutureDated(LocalDate.now());
        if (removed > 0) {
            log.info("roster.legacy-cleanup removed={} rows the monthly default-roster job had "
                    + "written ahead of today - those days are now derived from each employee's "
                    + "own configured weekly off instead", removed);
        }
    }

    /**
     * Removes generator-written rows dated {@code from} or later.
     *
     * <p>Today itself is included: it has not been generated against yet in the
     * run that matters, and leaving it would give one last day of the hardcoded
     * Sunday to an employee whose week-off is something else.
     *
     * @return how many rows were removed
     */
    @Transactional
    public int removeFutureDated(LocalDate from) {
        List<ShiftSchedule> legacy = shiftScheduleRepository
                .findAllByAssignedByAndShiftDateGreaterThanEqual(LEGACY_ASSIGNED_BY, from);
        if (legacy.isEmpty()) {
            return 0;
        }
        shiftScheduleRepository.deleteAll(legacy);
        return legacy.size();
    }
}
