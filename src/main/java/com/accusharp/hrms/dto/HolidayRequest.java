package com.accusharp.hrms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class HolidayRequest {

    private Long companyId;

    @NotBlank
    private String holidayName;

    @NotNull
    private LocalDate holidayDate;

    private boolean optionalHoliday;

    @Size(max = 500)
    private String description;
}
