package com.accusharp.hrms.dto;

import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.enums.AttendanceRecordStatus;
import com.accusharp.hrms.enums.AttendanceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A stored attendance day, provenance included. {@code recordStatus} says who
 * produced it and {@code locked} whether payroll has frozen it - a UI badge is
 * {@code locked ? "LOCKED" : recordStatus}.
 */
public record AttendanceRecordResponse(
        Long id,
        String userId,
        LocalDate attendanceDate,
        String shiftCode,
        LocalDateTime firstIn,
        LocalDateTime lastOut,
        BigDecimal workingHours,
        BigDecimal breakHours,
        BigDecimal overtimeHours,
        int lateMinutes,
        int earlyExitMinutes,
        boolean invalidPunch,
        boolean weekOff,
        boolean holiday,
        AttendanceStatus status,
        AttendanceRecordStatus recordStatus,
        boolean locked,
        String remarks,
        Instant generatedAt,
        String generatedBy,
        Instant updatedAt,
        String updatedBy
) {

    public static AttendanceRecordResponse of(DailyAttendance record) {
        return new AttendanceRecordResponse(record.getId(), record.getUserId(), record.getAttendanceDate(),
                record.getShiftCode(), record.getFirstIn(), record.getLastOut(), record.getWorkingHours(),
                record.getBreakHours(), record.getOvertimeHours(), record.getLateMinutes(),
                record.getEarlyExitMinutes(), record.isInvalidPunch(), record.isWeekOff(), record.isHoliday(),
                record.getStatus(), record.getRecordStatus(), record.isLocked(), record.getRemarks(),
                record.getGeneratedAt(), record.getGeneratedBy(), record.getUpdatedAt(), record.getUpdatedBy());
    }
}
