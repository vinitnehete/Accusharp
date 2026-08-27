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

    /**
     * Every punch that could belong to any day in a range, in one query -
     * inclusive at both ends, because a punch at a shift's exact scheduled end
     * belongs to that shift. Which day each punch actually lands on is decided
     * by {@code AttendanceWindowResolver}, not by this predicate: the engine
     * fetches once per employee per range and partitions in memory, rather
     * than issuing one range query per rostered day.
     */
    List<DeviceLog> findAllByUserIdAndLogDateBetweenOrderByLogDateAsc(
            String userId, LocalDateTime fromInclusive, LocalDateTime toInclusive);

    /** Batched form of {@link #findAllByUserIdAndLogDateBetweenOrderByLogDateAsc}. */
    List<DeviceLog> findAllByUserIdInAndLogDateBetweenOrderByLogDateAsc(
            List<String> userIds, LocalDateTime fromInclusive, LocalDateTime toInclusive);
}
