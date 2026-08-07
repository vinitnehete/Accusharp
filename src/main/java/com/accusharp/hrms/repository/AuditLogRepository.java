package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByCompanyIdOrderByTimestampDesc(Long companyId, Pageable pageable);

    List<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);
}
