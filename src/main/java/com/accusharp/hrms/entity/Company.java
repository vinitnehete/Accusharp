package com.accusharp.hrms.entity;

import com.accusharp.hrms.enums.RecordStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "company", uniqueConstraints = @UniqueConstraint(columnNames = "company_code"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_code", nullable = false, length = 30)
    private String companyCode;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(length = 500)
    private String address;

    @Column(length = 20)
    private String phone;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecordStatus status;

    /**
     * The month this company's leave year starts: 1 (January, the calendar year)
     * or 4 (April, the financial year). Balances are numbered by the year their
     * leave year starts in, so for a calendar company nothing about them changes.
     * Switched only through {@code LeaveSettingsService}, and only while the
     * company has no leave taken, pending or credited.
     */
    @Column(name = "leave_year_start_month", nullable = false, columnDefinition = "int not null default 1")
    @Builder.Default
    private int leaveYearStartMonth = 1;
}
