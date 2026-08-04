package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.HolidayRequest;
import com.accusharp.hrms.entity.Holiday;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Company holiday calendar. Attendance, leave, payroll and the shift planner
 * all read it, so it is the single place a non-working day is declared.
 */
@Service
@RequiredArgsConstructor
public class HolidayService {

    private final HolidayRepository holidayRepository;
    private final CompanyService companyService;

    @Transactional
    public Holiday create(HolidayRequest request) {
        return holidayRepository.save(apply(new Holiday(), request));
    }

    @Transactional
    public Holiday update(Long id, HolidayRequest request) {
        return holidayRepository.save(apply(getById(id), request));
    }

    @Transactional(readOnly = true)
    public Holiday getById(Long id) {
        return holidayRepository.findById(id).orElseThrow(() -> NotFoundException.of("Holiday", id));
    }

    @Transactional(readOnly = true)
    public List<Holiday> getAll() {
        return holidayRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Holiday> getBetween(LocalDate fromDate, LocalDate toDate) {
        return holidayRepository.findAllByHolidayDateBetweenOrderByHolidayDateAsc(fromDate, toDate);
    }

    /** Mandatory holidays only - optional ones stay working days. */
    @Transactional(readOnly = true)
    public Set<LocalDate> mandatoryHolidayDates(LocalDate fromDate, LocalDate toDate) {
        return holidayRepository.findAllByHolidayDateBetweenOrderByHolidayDateAsc(fromDate, toDate).stream()
                .filter(holiday -> !holiday.isOptionalHoliday())
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public Set<LocalDate> mandatoryHolidayDates(YearMonth month) {
        return mandatoryHolidayDates(month.atDay(1), month.atEndOfMonth());
    }

    @Transactional
    public void delete(Long id) {
        holidayRepository.delete(getById(id));
    }

    private Holiday apply(Holiday holiday, HolidayRequest request) {
        holiday.setCompany(request.getCompanyId() == null ? null : companyService.getById(request.getCompanyId()));
        holiday.setHolidayName(request.getHolidayName());
        holiday.setHolidayDate(request.getHolidayDate());
        holiday.setOptionalHoliday(request.isOptionalHoliday());
        holiday.setDescription(request.getDescription());
        return holiday;
    }
}
