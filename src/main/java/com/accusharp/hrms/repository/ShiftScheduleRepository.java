package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.ShiftSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShiftScheduleRepository extends JpaRepository<ShiftSchedule, Long> {

    Optional<ShiftSchedule> findByUserIdAndShiftDate(String userId, LocalDate shiftDate);

    List<ShiftSchedule> findAllByUserIdOrderByShiftDateAsc(String userId);

    List<ShiftSchedule> findAllByUserIdAndShiftDateBetweenOrderByShiftDateAsc(
            String userId, LocalDate fromDate, LocalDate toDate);

    List<ShiftSchedule> findAllByShiftDateBetween(LocalDate fromDate, LocalDate toDate);

    List<ShiftSchedule> findAllByUserIdInAndShiftDateBetween(
            List<String> userIds, LocalDate fromDate, LocalDate toDate);

    long countByShiftId(Long shiftId);

    void deleteByUserIdAndShiftDateBetween(String userId, LocalDate fromDate, LocalDate toDate);
}
