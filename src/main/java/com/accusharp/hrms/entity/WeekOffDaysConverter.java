package com.accusharp.hrms.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stores an employee's week-off days as a comma-separated list of day names -
 * {@code "SATURDAY,SUNDAY"}.
 *
 * <p><b>Why one column rather than a join table.</b> Attendance generation
 * reads this for every employee in the run, inside the loop that builds their
 * roster. An {@code @ElementCollection} would be either a second query per
 * employee or an eager join on the single most-read entity in the application;
 * a converted column comes back with the row that was being fetched anyway.
 * Nothing queries <em>by</em> week-off day, so the one thing a join table would
 * buy is not needed.
 *
 * <p><b>Why day names rather than a bitmask.</b> Seven bits in an integer would
 * be smaller and completely opaque. When a client asks why an employee was
 * marked absent on a Tuesday, the answer has to be readable in the row.
 *
 * <p><b>Null and empty are different, and the difference is load-bearing.</b>
 * Null means nobody has configured this employee - see
 * {@link Employee#hasConfiguredWeekOffOn}, which resolves it to Sunday for an
 * auto-rostered employee - the day the deleted {@code DefaultRosterService}
 * hardcoded for them - and to no weekly off for anyone else. An empty
 * string means somebody has said, explicitly, that this employee has no weekly
 * off. Collapsing the two would make "works every day" unsayable, and would
 * silently give a Sunday off to an employee whose contract does not include
 * one.
 *
 * <p>Order is not preserved and does not matter: this is a set of days, and it
 * is normalised to calendar order on write so the stored value is stable
 * whatever order the caller supplied.
 */
@Converter(autoApply = false)
public class WeekOffDaysConverter implements AttributeConverter<Set<DayOfWeek>, String> {

    private static final String SEPARATOR = ",";

    @Override
    public String convertToDatabaseColumn(Set<DayOfWeek> days) {
        if (days == null) {
            return null;
        }
        // EnumSet iterates in calendar order, so MONDAY,SUNDAY and SUNDAY,MONDAY
        // both store as MONDAY,SUNDAY - one value per meaning.
        return days.isEmpty() ? "" : EnumSet.copyOf(days).stream()
                .map(DayOfWeek::name)
                .collect(Collectors.joining(SEPARATOR));
    }

    @Override
    public Set<DayOfWeek> convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(stored.split(SEPARATOR))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }
}
