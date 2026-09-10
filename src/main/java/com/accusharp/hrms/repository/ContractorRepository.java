package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.enums.RecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractorRepository extends JpaRepository<Contractor, Long> {

    List<Contractor> findByCompanyIdOrderByContractorNameAsc(Long companyId);

    List<Contractor> findByCompanyIdAndRecordStatusOrderByContractorNameAsc(Long companyId,
                                                                            RecordStatus recordStatus);

    Optional<Contractor> findByCompanyIdAndContractorCode(Long companyId, String contractorCode);

    boolean existsByCompanyIdAndContractorCode(Long companyId, String contractorCode);
}
