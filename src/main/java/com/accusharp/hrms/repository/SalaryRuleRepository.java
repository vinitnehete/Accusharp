package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.SalaryRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SalaryRuleRepository extends JpaRepository<SalaryRule, Long> {

    Optional<SalaryRule> findByCompanyId(Long companyId);

    Optional<SalaryRule> findByCompanyIsNull();
}
