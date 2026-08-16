package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.CategoryRequest;
import com.accusharp.hrms.entity.Category;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CategoryRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * One row per company, plus shared ({@code company = null}) rows every
 * company can read - see {@link Category}'s Javadoc and {@code
 * Department}'s for the full rationale, mirrored here identically. Write
 * access ({@link #update}/{@link #delete}) is restricted to the caller's own
 * company's rows, so no company can edit a shared row or another company's
 * row.
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;

    @Transactional
    public Category create(CategoryRequest request) {
        Long companyId = tenantContext.currentCompanyId().orElse(null);
        if (codeInUse(request.getCategoryCode(), companyId)) {
            throw new ConflictException("Category already exists with code " + request.getCategoryCode());
        }
        Category category = apply(new Category(), request);
        category.setCompany(companyId == null ? null : companyRepository.getReferenceById(companyId));
        return categoryRepository.save(category);
    }

    @Transactional
    public Category update(Long id, CategoryRequest request) {
        Category category = getById(id);
        assertWritable(category);
        Long companyId = category.getCompany() == null ? null : category.getCompany().getId();
        findByCode(request.getCategoryCode(), companyId)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another category already uses code "
                            + request.getCategoryCode());
                });
        return categoryRepository.save(apply(category, request));
    }

    @Transactional(readOnly = true)
    public Category getById(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Category", id));
        assertReadable(category);
        return category;
    }

    @Transactional(readOnly = true)
    public List<Category> getAll() {
        return tenantContext.currentCompanyId()
                .map(categoryRepository::findByCompanyIdOrCompanyIsNull)
                .orElseGet(categoryRepository::findAll);
    }

    @Transactional
    public void delete(Long id) {
        Category category = getById(id);
        assertWritable(category);
        if (!employeeRepository.findByCategoryId(id).isEmpty()) {
            throw new ConflictException("Category still has employees assigned");
        }
        categoryRepository.delete(category);
    }

    private boolean codeInUse(String code, Long companyId) {
        if (companyId == null) {
            return categoryRepository.existsByCategoryCodeAndCompanyIsNull(code);
        }
        return categoryRepository.existsByCategoryCodeAndCompanyId(code, companyId)
                || categoryRepository.existsByCategoryCodeAndCompanyIsNull(code);
    }

    private Optional<Category> findByCode(String code, Long companyId) {
        if (companyId == null) {
            return categoryRepository.findByCategoryCodeAndCompanyIsNull(code);
        }
        return categoryRepository.findByCategoryCodeAndCompanyId(code, companyId)
                .or(() -> categoryRepository.findByCategoryCodeAndCompanyIsNull(code));
    }

    /** A caller scoped to one company may read their own company's rows and every shared row. */
    private void assertReadable(Category category) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = category.getCompany() == null ? null : category.getCompany().getId();
            if (targetCompanyId != null && !callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Category", category.getId());
            }
        });
    }

    /** Unlike read access, a shared (company-null) row is never writable by a company caller. */
    private void assertWritable(Category category) {
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = category.getCompany() == null ? null : category.getCompany().getId();
            if (!callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Category", category.getId());
            }
        });
    }

    private Category apply(Category category, CategoryRequest request) {
        category.setCategoryCode(request.getCategoryCode());
        category.setCategoryName(request.getCategoryName());
        category.setDescription(request.getDescription());
        return category;
    }
}
