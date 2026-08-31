package com.accusharp.hrms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The employee category/grade (Worker, Supervisor, Manager, Director, ...).
 * One row per company, plus rows with {@code company = null} - a shared
 * catalog every company can see and use, editable only by a platform caller.
 * See {@code Department}'s Javadoc for the full rationale, mirrored here
 * identically - a company may add as many categories as it needs, the same
 * way it manages departments and designations.
 */
@Entity
@Table(name = "category", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "category_code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means a shared row every company can see. See class Javadoc. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Column(name = "category_name", nullable = false)
    private String categoryName;

    @Column(length = 500)
    private String description;
}
