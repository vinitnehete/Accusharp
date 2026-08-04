package com.accusharp.hrms.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftScheduleResponse(
        Long id,
        String userId,
        LocalDate shiftDate,
        String shiftCode,
        String shiftName,
        LocalTime startTime,
        LocalTime endTime,
        boolean weekOff,
        String assignedBy
) {
}
