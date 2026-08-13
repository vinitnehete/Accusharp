package com.accusharp.hrms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.YearMonth;
import java.util.List;

/**
 * Generates the stored attendance for a period, for one employee, several, or
 * everybody.
 */
@Data
public class AttendanceGenerationRequest {

    @NotNull
    @JsonFormat(pattern = "yyyy-MM")
    private YearMonth month;

    /** Null or empty means every active employee. */
    private List<String> userIds;

    /** Whoever ran it - must hold the HR or ADMIN role. */
    @NotBlank
    private String generatedBy;

    /**
     * Off by default, and that default is the point: a rerun picks up punches
     * that arrived late without discarding what an admin already corrected.
     */
    private boolean overwriteManual;
}
