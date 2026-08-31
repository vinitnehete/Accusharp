package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.AttendanceRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttendanceRuleRepository extends JpaRepository<AttendanceRule, Long> {

    Optional<AttendanceRule> findByCompanyId(Long companyId);

    Optional<AttendanceRule> findByCompanyIsNull();
}
