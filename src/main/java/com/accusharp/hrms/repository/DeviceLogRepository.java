package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.DeviceLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only view of the punches the biometric device writes. Grouping into
 * days happens in the service layer rather than in SQL, so the query stays
 * portable across MySQL and H2.
 */
public interface DeviceLogRepository extends JpaRepository<DeviceLog, Long> {

    List<DeviceLog> findAllByUserIdAndLogDateGreaterThanEqualAndLogDateLessThanOrderByLogDateAsc(
            String userId, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    List<DeviceLog> findAllByUserIdInAndLogDateGreaterThanEqualAndLogDateLessThanOrderByLogDateAsc(
            List<String> userIds, LocalDateTime fromInclusive, LocalDateTime toExclusive);
}
