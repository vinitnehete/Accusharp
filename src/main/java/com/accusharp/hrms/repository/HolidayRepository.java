package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findAllByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate fromDate, LocalDate toDate);

    boolean existsByHolidayDateAndOptionalHolidayFalse(LocalDate holidayDate);
}
