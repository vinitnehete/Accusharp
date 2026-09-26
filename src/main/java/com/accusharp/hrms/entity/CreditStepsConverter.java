package com.accusharp.hrms.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stores an earned-leave rule's steps in one readable column -
 * {@code "20=1.0;10=0.5"} - for the same reasons {@link WeekOffDaysConverter}
 * stores day names: it is read with the rule anyway, nothing queries by it, and
 * whoever supports the system can read it straight off the row.
 *
 * <p>Normalised to highest-first on write, so the stored value is stable
 * whatever order the caller supplied. Null stays null: the rule then uses the
 * calculator's defaults.
 */
@Converter(autoApply = false)
public class CreditStepsConverter implements AttributeConverter<List<CreditStep>, String> {

    @Override
    public String convertToDatabaseColumn(List<CreditStep> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
                .sorted(Comparator.comparingInt(CreditStep::minDays).reversed())
                .map(step -> step.minDays() + "=" + step.credit().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(";"));
    }

    @Override
    public List<CreditStep> convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(";"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(part -> {
                    String[] pair = part.split("=");
                    return new CreditStep(Integer.parseInt(pair[0].trim()), new BigDecimal(pair[1].trim()));
                })
                .toList();
    }
}
