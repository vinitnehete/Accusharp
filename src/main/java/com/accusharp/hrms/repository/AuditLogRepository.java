package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByCompanyIdOrderByTimestampDesc(Long companyId, Pageable pageable);

    List<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    /** Unbounded (no {@link Pageable} cap) - for export, where the 200-row read cap would silently drop history. */
    List<AuditLog> findAllByCompanyIdAndTimestampBetweenOrderByTimestampDesc(
            Long companyId, Instant from, Instant to);

    List<AuditLog> findAllByTimestampBetweenOrderByTimestampDesc(Instant from, Instant to);

    long deleteByTimestampBefore(Instant cutoff);
}
