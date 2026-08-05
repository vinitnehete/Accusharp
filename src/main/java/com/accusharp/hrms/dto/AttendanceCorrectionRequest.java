package com.accusharp.hrms.dto;

import com.accusharp.hrms.enums.AttendanceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * An admin's correction to one generated attendance day.
 *
 * <p>Two mutually supportive modes, because the real cases differ:
 * <ul>
 *   <li><b>corrected punches</b> - the device missed an out-punch, so supply
 *       the times and let the engine derive hours, lateness and overtime
 *       through the exact same rules a device punch would have taken;</li>
 *   <li><b>forced status</b> - no punch exists to correct (device down, or the
 *       employee worked off-site), so the day is simply declared.</li>
 * </ul>
 * At least one of the two must be present.
 */
@Data
public class AttendanceCorrectionRequest {

    private LocalDateTime firstIn;

    private LocalDateTime lastOut;

    /** Declares the day outright when there are no punch times to give. */
    private AttendanceStatus status;

    /** Mandatory - a correction without a reason is not auditable. */
    @NotBlank
    @Size(max = 500)
    private String remarks;

    /** Whoever made the change - must hold the HR or ADMIN role. */
    @NotBlank
    private String updatedBy;
}
