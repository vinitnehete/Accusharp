package com.accusharp.hrms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "holiday",
        uniqueConstraints = @UniqueConstraint(name = "uk_holiday_company_date",
                columnNames = {"company_id", "holiday_date"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code @JsonIgnore}: {@code HolidayController} returns this entity
     * directly, and with {@code spring.jpa.open-in-view=false} (the
     * production setting) the Hibernate session is already closed by
     * serialization time, so touching this lazy proxy throws instead of
     * returning null - proven empirically while fixing the identical issue
     * on {@code SalaryRule.company}, see that class's Javadoc.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "holiday_name", nullable = false)
    private String holidayName;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    /** Optional holidays are not auto-deducted from working days. */
    @Column(name = "optional_holiday", nullable = false)
    private boolean optionalHoliday;

    @Column(length = 500)
    private String description;
}
