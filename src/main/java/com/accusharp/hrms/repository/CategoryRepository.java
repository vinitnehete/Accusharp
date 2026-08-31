package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByCompanyIdOrCompanyIsNull(Long companyId);

    Optional<Category> findByCategoryCodeAndCompanyId(String categoryCode, Long companyId);

    Optional<Category> findByCategoryCodeAndCompanyIsNull(String categoryCode);

    boolean existsByCategoryCodeAndCompanyId(String categoryCode, Long companyId);

    boolean existsByCategoryCodeAndCompanyIsNull(String categoryCode);
}
