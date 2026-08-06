package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    @Query("select h from Holiday h left join fetch h.company order by h.holidayDate asc")
    List<Holiday> findAllWithCompany();

    @Query("select h from Holiday h left join fetch h.company "
            + "where h.holidayDate between :fromDate and :toDate order by h.holidayDate asc")
    List<Holiday> findAllByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate fromDate, LocalDate toDate);

    boolean existsByHolidayDateAndOptionalHolidayFalse(LocalDate holidayDate);
}
