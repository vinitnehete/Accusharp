package com.accusharp.hrms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "designation", uniqueConstraints = @UniqueConstraint(columnNames = "designation_code"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Designation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "designation_code", nullable = false, length = 30)
    private String designationCode;

    @Column(name = "designation_name", nullable = false)
    private String designationName;

    @Column(length = 500)
    private String description;
}
