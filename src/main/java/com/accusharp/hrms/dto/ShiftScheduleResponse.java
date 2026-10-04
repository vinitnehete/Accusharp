package com.accusharp.hrms.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * {@code defaulted} is true for a day nobody planned: the usual shift (or weekly
 * off) the attendance engine assumes for an employee on an automatic roster.
 * Only a roster read that asked for those days ({@code includeUsual}) can carry
 * one, and such a day has no {@code id} - it is derived, never stored.
 */
public record ShiftScheduleResponse(
        Long id,
        String userId,
        LocalDate shiftDate,
        String shiftCode,
        String shiftName,
        LocalTime startTime,
        LocalTime endTime,
        boolean weekOff,
        String assignedBy,
        boolean defaulted
) {
}
