package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.StatutoryDeduction;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A set of deductions as one text column, {@code "PF,ESIC"}. Null or blank
 * reads as none, so rows written before the column existed exclude nothing.
 */
@Converter
public class StatutoryDeductionsConverter implements AttributeConverter<Set<StatutoryDeduction>, String> {

    @Override
    public String convertToDatabaseColumn(Set<StatutoryDeduction> deductions) {
        return deductions == null || deductions.isEmpty() ? null
                : deductions.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    @Override
    public Set<StatutoryDeduction> convertToEntityAttribute(String column) {
        EnumSet<StatutoryDeduction> deductions = EnumSet.noneOf(StatutoryDeduction.class);
        if (column != null && !column.isBlank()) {
            Arrays.stream(column.split(",")).map(String::trim).map(StatutoryDeduction::valueOf).forEach(deductions::add);
        }
        return deductions;
    }
}
