package com.accusharp.hrms.dto.policy;

import com.accusharp.hrms.enums.AttendanceStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Re-evaluates a past month under a proposed rule set, without persisting anything. */
public final class AttendancePolicyPreviewDtos {

    private AttendancePolicyPreviewDtos() {
    }

    @Data
    public static class PreviewRequest {

        @NotNull
        @JsonFormat(pattern = "yyyy-MM")
        private YearMonth month;

        /** Null or empty means every active employee in the caller's company. */
        private List<String> userIds;

        /**
         * The rules to evaluate the month under, <b>instead of</b> whatever is
         * currently stored. Not merged with the stored set: a preview answers
         * "what would this configuration produce", and silently blending it with
         * existing rules would answer a question nobody asked.
         */
        @NotEmpty
        @Valid
        private List<AttendancePolicyRuleRequest> rules;
    }

    /**
     * @param lopDelta      the figure that decides whether this rule set is
     *                      safe to save. Positive means employees lose pay.
     * @param employees     per-employee detail, worst LOP delta first
     */
    public record PreviewResponse(YearMonth month, int employeesEvaluated,
                                  int employeesAffected, BigDecimal lopDelta,
                                  BigDecimal overtimeHoursDelta,
                                  List<String> warnings,
                                  List<EmployeePreview> employees) {
    }

    public record EmployeePreview(String userId, String employeeName,
                                  BigDecimal lopBefore, BigDecimal lopAfter, BigDecimal lopDelta,
                                  BigDecimal overtimeBefore, BigDecimal overtimeAfter,
                                  List<DayChange> dayChanges,
                                  List<String> monthOutcomes) {
    }

    public record DayChange(LocalDate date, AttendanceStatus before, AttendanceStatus after,
                            String reason) {
    }
}
