package com.accusharp.hrms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per company, plus rows with {@code company = null} - a shared
 * catalog every company can see and use, editable only by a platform
 * caller. See {@code Department}'s Javadoc for the full rationale.
 */
@Entity
@Table(name = "designation", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "designation_code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Designation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means a shared row every company can see. See class Javadoc. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "designation_code", nullable = false, length = 30)
    private String designationCode;

    @Column(name = "designation_name", nullable = false)
    private String designationName;

    @Column(length = 500)
    private String description;
}
