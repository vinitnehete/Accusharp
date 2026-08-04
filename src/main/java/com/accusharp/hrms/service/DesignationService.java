package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.DesignationRequest;
import com.accusharp.hrms.entity.Designation;
import com.accusharp.hrms.exception.ConflictException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.DesignationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DesignationService {

    private final DesignationRepository designationRepository;

    @Transactional
    public Designation create(DesignationRequest request) {
        if (designationRepository.existsByDesignationCode(request.getDesignationCode())) {
            throw new ConflictException("Designation already exists with code " + request.getDesignationCode());
        }
        return designationRepository.save(apply(new Designation(), request));
    }

    @Transactional
    public Designation update(Long id, DesignationRequest request) {
        Designation designation = getById(id);
        designationRepository.findByDesignationCode(request.getDesignationCode())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("Another designation already uses code "
                            + request.getDesignationCode());
                });
        return designationRepository.save(apply(designation, request));
    }

    @Transactional(readOnly = true)
    public Designation getById(Long id) {
        return designationRepository.findById(id).orElseThrow(() -> NotFoundException.of("Designation", id));
    }

    @Transactional(readOnly = true)
    public List<Designation> getAll() {
        return designationRepository.findAll();
    }

    @Transactional
    public void delete(Long id) {
        designationRepository.delete(getById(id));
    }

    private Designation apply(Designation designation, DesignationRequest request) {
        designation.setDesignationCode(request.getDesignationCode());
        designation.setDesignationName(request.getDesignationName());
        designation.setDescription(request.getDescription());
        return designation;
    }
}
