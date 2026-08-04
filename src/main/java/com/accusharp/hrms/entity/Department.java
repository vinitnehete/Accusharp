package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "department", uniqueConstraints = @UniqueConstraint(columnNames = "department_code"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "department_code", nullable = false, length = 30)
    private String departmentCode;

    @Column(name = "department_name", nullable = false)
    private String departmentName;

    @Column(length = 500)
    private String description;
}
