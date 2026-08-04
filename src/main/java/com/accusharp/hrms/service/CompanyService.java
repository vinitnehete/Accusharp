package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.CompanyRequest;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;

    @Transactional
    public Company create(CompanyRequest request) {
        if (companyRepository.existsByCompanyCode(request.getCompanyCode())) {
            throw new ConflictException("Company already exists with code " + request.getCompanyCode());
        }
        return companyRepository.save(apply(new Company(), request));
    }

    @Transactional
    public Company update(Long id, CompanyRequest request) {
        Company company = getById(id);
        companyRepository.findByCompanyCode(request.getCompanyCode())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another company already uses code " + request.getCompanyCode());
                });
        return companyRepository.save(apply(company, request));
    }

    @Transactional(readOnly = true)
    public Company getById(Long id) {
        return companyRepository.findById(id).orElseThrow(() -> NotFoundException.of("Company", id));
    }

    @Transactional(readOnly = true)
    public List<Company> getAll() {
        return companyRepository.findAll();
    }

    @Transactional
    public void delete(Long id) {
        companyRepository.delete(getById(id));
    }

    private Company apply(Company company, CompanyRequest request) {
        company.setCompanyCode(request.getCompanyCode());
        company.setCompanyName(request.getCompanyName());
        company.setAddress(request.getAddress());
        company.setPhone(request.getPhone());
        company.setEmail(request.getEmail());
        company.setStatus(request.getStatus());
        return company;
    }
}
