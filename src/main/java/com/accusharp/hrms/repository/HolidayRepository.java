package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    @Query("select h from Holiday h left join fetch h.company order by h.holidayDate asc")
    List<Holiday> findAllWithCompany();

    @Query("select h from Holiday h left join fetch h.company where h.company.id = :companyId "
            + "order by h.holidayDate asc")
    List<Holiday> findAllByCompanyId(Long companyId);

    /**
     * Deliberately unscoped by company - kept only for the two call sites that
     * need it (see {@code HolidayService.mandatoryHolidayDates}'s Javadoc):
     * company-less test/internal callers, where "unscoped" already matches
     * today's behavior for every other tenant check in this app.
     */
    @Query("select h from Holiday h left join fetch h.company "
            + "where h.holidayDate between :fromDate and :toDate order by h.holidayDate asc")
    List<Holiday> findAllByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate fromDate, LocalDate toDate);

    @Query("select h from Holiday h left join fetch h.company where h.company.id = :companyId "
            + "and h.holidayDate between :fromDate and :toDate order by h.holidayDate asc")
    List<Holiday> findAllByCompanyIdAndHolidayDateBetweenOrderByHolidayDateAsc(
            Long companyId, LocalDate fromDate, LocalDate toDate);
}
