package com.accusharp.hrms.service.calculation;

import com.accusharp.hrms.entity.AttendanceRule;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which attendance day each punch belongs to.
 *
 * <p>A rostered day claims two spans, and the difference between them is the
 * whole of this class:
 *
 * <pre>
 *   soft head        CORE (contractual)         soft tail
 *  [-----------|==========================|-----------------)
 *   start-buffer  shiftStart     scheduledEnd   +overtimeWindow
 * </pre>
 *
 * <p>The <b>core</b> is the shift the employee was actually rostered to work.
 * It crosses midnight for a night shift, and it is <em>inviolable</em>: no
 * adjacent assignment may take a punch out of it. The soft head (people badge
 * in early) and soft tail (people work over) are conveniences, and they yield.
 *
 * <p>This replaces an earlier design that computed each day's window by
 * intersecting it with the <em>next</em> day's window start. That clamp had no
 * floor, so the next day's sixty-minute entry buffer could shorten a night
 * shift's window to before the night shift's own end time - a night worker who
 * punched out at the scheduled hour had the exit discarded, was left holding
 * one punch, and lost the day to {@code INVALID_PUNCH} and a day's pay. With
 * the seeded shifts it took only a {@code GENERAL} day after a {@code NIGHT}
 * day to happen, and it happened to whoever punched out on time.
 *
 * <p>Punches are <b>partitioned</b> rather than range-queried per day, so
 * "every punch belongs to at most one day" holds by construction instead of
 * emerging from interval arithmetic - and the awkward question of which day
 * owns the instant on a boundary is answered by an explicit rule rather than
 * by whether a SQL predicate happened to be inclusive.
 */
@Service
@RequiredArgsConstructor
public class AttendanceWindowResolver {

    private final AttendanceCalculationService attendanceCalculationService;

    /**
     * One rostered day's claim on the timeline.
     *
     * @param defendsCore false for a weekly off - a day nobody was expected to
     *                    work has no contractual span to defend, so it never
     *                    takes a punch out of the shift before it. It keeps its
     *                    soft window, because someone who turns up on their day
     *                    off is still {@code PRESENT}.
     */
    public record DayWindow(ShiftSchedule schedule,
                            LocalDateTime coreStart, LocalDateTime coreEnd,
                            LocalDateTime softStart, LocalDateTime softEnd,
                            boolean defendsCore) {

        public LocalDate date() {
            return schedule.getShiftDate();
        }

        /** Inside the contractual shift. Inclusive of the scheduled end: that punch is the exit. */
        boolean coreContains(LocalDateTime at) {
            return defendsCore && !at.isBefore(coreStart) && !at.isAfter(coreEnd);
        }

        /**
         * A candidate for this day at all. The core is always included even when
         * the soft tail is empty, so a shift configured with
         * {@code overtimeWindowMinutes = 0} still owns its own last minute.
         */
        boolean claims(LocalDateTime at) {
            boolean inSoft = !at.isBefore(softStart) && at.isBefore(softEnd);
            boolean inCore = !at.isBefore(coreStart) && !at.isAfter(coreEnd);
            return inSoft || inCore;
        }

        /** How far outside the core this punch sits; 0 when inside it. */
        Duration distanceToCore(LocalDateTime at) {
            if (at.isBefore(coreStart)) {
                return Duration.between(at, coreStart);
            }
            if (at.isAfter(coreEnd)) {
                return Duration.between(coreEnd, at);
            }
            return Duration.ZERO;
        }

        /** Earliest instant this day could possibly claim - the punch fetch's lower bound. */
        public LocalDateTime fetchFrom() {
            return softStart.isBefore(coreStart) ? softStart : coreStart;
        }

        /** Latest instant this day could possibly claim - the punch fetch's upper bound. */
        public LocalDateTime fetchTo() {
            return softEnd.isAfter(coreEnd) ? softEnd : coreEnd;
        }
    }

    /**
     * The outcome of a partition.
     *
     * @param punchesByDate  each day's punches, ascending, disjoint across days
     * @param conflictDates  days whose cores physically overlap a neighbour's -
     *                       a roster nobody could work. The punches are still
     *                       assigned deterministically, but the day is flagged
     *                       so it can be surfaced rather than silently absorbed.
     * @param unassigned     punches inside no day's claim at all. Not an error -
     *                       an unrostered day, or a gap between two shifts - but
     *                       worth counting rather than dropping in silence.
     */
    public record Resolution(Map<LocalDate, List<DeviceLog>> punchesByDate,
                             Set<LocalDate> conflictDates,
                             List<DeviceLog> unassigned) {

        public List<DeviceLog> punchesOn(LocalDate date) {
            return punchesByDate.getOrDefault(date, List.of());
        }
    }

    /**
     * Builds each rostered day's claim. Unlike the windowing this replaced, a
     * day's span is computed from that day alone - neighbours are consulted
     * when punches are assigned, not when spans are built, so a day's window
     * can never be shortened below the shift it represents.
     *
     * @param roster ascending by {@code shiftDate}
     */
    public List<DayWindow> windowsFor(List<ShiftSchedule> roster, AttendanceRule rule) {
        List<DayWindow> windows = new ArrayList<>(roster.size());
        for (ShiftSchedule schedule : roster) {
            LocalDate date = schedule.getShiftDate();
            Shift shift = schedule.getShift();

            LocalDateTime coreStart = date.atTime(shift.getStartTime());
            LocalDateTime coreEnd = attendanceCalculationService.scheduledEnd(date, shift);
            LocalDateTime softStart = attendanceCalculationService.windowStart(date, shift, rule);
            LocalDateTime softEnd = attendanceCalculationService.windowEnd(date, shift);

            windows.add(new DayWindow(schedule, coreStart, coreEnd, softStart, softEnd,
                    !schedule.isWeekOff()));
        }
        return windows;
    }

    /** Earliest instant any of these days could claim, for a single punch fetch. */
    public LocalDateTime fetchFrom(List<DayWindow> windows) {
        return windows.stream().map(DayWindow::fetchFrom).min(LocalDateTime::compareTo).orElse(null);
    }

    /** Latest instant any of these days could claim, for a single punch fetch. */
    public LocalDateTime fetchTo(List<DayWindow> windows) {
        return windows.stream().map(DayWindow::fetchTo).max(LocalDateTime::compareTo).orElse(null);
    }

    /**
     * Partitions punches across days. Every punch lands on at most one day, and
     * no punch inside a shift's core is discarded.
     *
     * <p>Where two days both claim a punch the tie is broken in a fixed order:
     *
     * <ol>
     *   <li>A day that has <b>independent evidence</b> of having been worked -
     *       at least one punch no other day can claim - beats a day that has
     *       none. Only applied when it actually discriminates: if both days
     *       have their own evidence, or neither does, this decides nothing and
     *       the rules below take over.</li>
     *   <li>A day whose <b>core</b> contains it beats a day that only claims it
     *       softly. This is what stops the next morning's entry buffer taking
     *       the night shift's exit, and equally stops the night shift's
     *       overtime tail taking the morning's entry.</li>
     *   <li>If <b>both cores</b> contain it the roster is physically unworkable
     *       (a night shift ending 08:00 followed by a morning starting 06:00).
     *       The earlier shift date wins, and both days are flagged.</li>
     *   <li>If <b>neither core</b> does, it belongs to whichever core is nearer -
     *       an overtime punch stays with the shift that ran over, an early
     *       arrival goes to the shift about to start. Ties go to the earlier
     *       date.</li>
     * </ol>
     *
     * <p>Rule 1 exists because rule 3 alone gets a real and common case badly
     * wrong. On the unworkable {@code NIGHT -> MORNING} pair, a punch at 07:18
     * is inside both cores. If the employee simply did not work the night shift
     * - no evening punch anywhere - handing 07:18 to the night on seniority of
     * date invents a night shift nobody worked and strands the morning holding
     * only its own exit punch, so <em>both</em> days read {@code INVALID_PUNCH}
     * and both become loss of pay. Asking first whether either day has a punch
     * that is unambiguously its own settles it on evidence rather than on
     * calendar order. It is not inference from punch clustering: a day either
     * has a punch no other day can claim, or it does not.
     *
     * @param punches ascending by {@code logDate}; duplicates already removed
     */
    public Resolution assign(List<DayWindow> windows, List<DeviceLog> punches) {
        Map<LocalDate, List<DeviceLog>> byDate = new HashMap<>();
        Set<LocalDate> conflicts = new LinkedHashSet<>();
        List<DeviceLog> unassigned = new ArrayList<>();

        Set<LocalDate> workedOnEvidence = daysWithOwnEvidence(windows, punches);

        for (DeviceLog punch : punches) {
            LocalDateTime at = punch.getLogDate();

            List<DayWindow> candidates = windows.stream().filter(w -> w.claims(at)).toList();
            if (candidates.isEmpty()) {
                unassigned.add(punch);
                continue;
            }

            // Flag the unworkable roster from the full candidate set, before the
            // evidence filter narrows it - the overlap is a property of the
            // roster and is worth reporting whichever day ends up with the punch.
            List<DayWindow> allCoreHits = candidates.stream().filter(w -> w.coreContains(at)).toList();
            if (allCoreHits.size() > 1) {
                allCoreHits.forEach(w -> conflicts.add(w.date()));
            }

            List<DayWindow> pool = preferDaysWithOwnEvidence(candidates, workedOnEvidence);

            DayWindow owner;
            List<DayWindow> coreHits = pool.stream().filter(w -> w.coreContains(at)).toList();

            if (coreHits.size() == 1) {
                owner = coreHits.getFirst();
            } else if (coreHits.size() > 1) {
                owner = coreHits.stream().min(Comparator.comparing(DayWindow::date)).orElseThrow();
            } else {
                owner = pool.stream()
                        .min(Comparator.comparing((DayWindow w) -> w.distanceToCore(at))
                                .thenComparing(DayWindow::date))
                        .orElseThrow();
            }

            byDate.computeIfAbsent(owner.date(), d -> new ArrayList<>()).add(punch);
        }

        byDate.values().forEach(list -> list.sort(Comparator.comparing(DeviceLog::getLogDate)));
        return new Resolution(byDate, conflicts, unassigned);
    }

    /**
     * Days holding at least one punch no other day can claim - the days that
     * demonstrably were worked, as opposed to the days the roster merely says
     * were scheduled. A punch claimed by exactly one window is that window's
     * own evidence; a punch two windows both want proves nothing about either.
     */
    private Set<LocalDate> daysWithOwnEvidence(List<DayWindow> windows, List<DeviceLog> punches) {
        Set<LocalDate> evidenced = new HashSet<>();
        for (DeviceLog punch : punches) {
            LocalDateTime at = punch.getLogDate();
            DayWindow sole = null;
            for (DayWindow window : windows) {
                if (!window.claims(at)) {
                    continue;
                }
                if (sole != null) {
                    sole = null;
                    break;
                }
                sole = window;
            }
            if (sole != null) {
                evidenced.add(sole.date());
            }
        }
        return evidenced;
    }

    /**
     * Narrows a contested punch to the days that were demonstrably worked -
     * but only when that actually separates them. If every candidate has its
     * own evidence, or none does, the question is left to the core and
     * proximity rules rather than being decided arbitrarily here.
     */
    private List<DayWindow> preferDaysWithOwnEvidence(List<DayWindow> candidates, Set<LocalDate> evidenced) {
        if (candidates.size() < 2) {
            return candidates;
        }
        List<DayWindow> worked = candidates.stream().filter(w -> evidenced.contains(w.date())).toList();
        return worked.isEmpty() || worked.size() == candidates.size() ? candidates : worked;
    }

    /**
     * Collapses punches that are the same physical badge read.
     *
     * <p>{@code device_logs} has no unique constraint on {@code (user_id,
     * log_date)} and {@code device_log_id} is explicitly not globally unique,
     * so a device re-sync can deliver one punch twice. Left in, two identical
     * rows read as a zero-length day - {@code ABSENT} - which is strictly worse
     * than the {@code INVALID_PUNCH} a single punch produces, because it hides
     * the device error instead of surfacing it for correction.
     *
     * @param punches ascending by {@code logDate}
     */
    public List<DeviceLog> dedupe(List<DeviceLog> punches) {
        List<DeviceLog> distinct = new ArrayList<>(punches.size());
        LocalDateTime previous = null;
        for (DeviceLog punch : punches) {
            if (!punch.getLogDate().equals(previous)) {
                distinct.add(punch);
                previous = punch.getLogDate();
            }
        }
        return distinct;
    }
}
