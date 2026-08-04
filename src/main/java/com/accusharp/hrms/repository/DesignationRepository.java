package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Designation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DesignationRepository extends JpaRepository<Designation, Long> {

    Optional<Designation> findByDesignationCode(String designationCode);

    boolean existsByDesignationCode(String designationCode);
}
