package com.accusharp.hrms.service;

import com.accusharp.hrms.dto.HolidayRequest;
import com.accusharp.hrms.entity.Holiday;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.HolidayRepository;
import com.accusharp.hrms.security.TenantContext;
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
 *
 * <p>Found during a full security audit and fixed here rather than merely
 * documented: {@link #getById} previously had no tenant check at all -
 * unlike {@code EmployeeService.getEntityById} - so any authenticated
 * HR/ADMIN of <em>any</em> company could update or delete <em>any other</em>
 * company's holiday by guessing its numeric id, directly corrupting that
 * company's attendance and payroll (a mandatory holiday deleted from another
 * tenant's calendar silently becomes loss of pay for everyone rostered that
 * day). {@code getAll}/{@code getBetween} previously mixed every company's
 * holidays into one unfiltered list.
 */
@Service
@RequiredArgsConstructor
public class HolidayService {

    private final HolidayRepository holidayRepository;
    private final CompanyService companyService;
    private final TenantContext tenantContext;

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
        Holiday holiday = holidayRepository.findById(id).orElseThrow(() -> NotFoundException.of("Holiday", id));
        tenantContext.currentCompanyId().ifPresent(callerCompanyId -> {
            Long targetCompanyId = holiday.getCompany() == null ? null : holiday.getCompany().getId();
            if (!callerCompanyId.equals(targetCompanyId)) {
                throw NotFoundException.of("Holiday", id);
            }
        });
        return holiday;
    }

    @Transactional(readOnly = true)
    public List<Holiday> getAll() {
        return tenantContext.currentCompanyId()
                .map(holidayRepository::findAllByCompanyId)
                .orElseGet(holidayRepository::findAllWithCompany);
    }

    @Transactional(readOnly = true)
    public List<Holiday> getBetween(LocalDate fromDate, LocalDate toDate) {
        return tenantContext.currentCompanyId()
                .map(companyId -> holidayRepository
                        .findAllByCompanyIdAndHolidayDateBetweenOrderByHolidayDateAsc(companyId, fromDate, toDate))
                .orElseGet(() -> holidayRepository
                        .findAllByHolidayDateBetweenOrderByHolidayDateAsc(fromDate, toDate));
    }

    /**
     * Mandatory holidays only - optional ones stay working days.
     *
     * <p>{@code companyId} is deliberately explicit here, not read from
     * {@link TenantContext} internally like the methods above: every caller
     * (the attendance engine, shift scheduling) already has the relevant
     * {@code Employee} in hand and must scope this to <em>that
     * employee's</em> company, which is not always the same principal as
     * whoever is authenticated on the current request (e.g. bulk operations
     * acting on someone else's roster). Passing {@code null} preserves the
     * pre-fix "every company's holidays" behavior, which is what every
     * existing test's company-less employees already resolve to.
     */
    @Transactional(readOnly = true)
    public Set<LocalDate> mandatoryHolidayDates(Long companyId, LocalDate fromDate, LocalDate toDate) {
        List<Holiday> holidays = companyId == null
                ? holidayRepository.findAllByHolidayDateBetweenOrderByHolidayDateAsc(fromDate, toDate)
                : holidayRepository.findAllByCompanyIdAndHolidayDateBetweenOrderByHolidayDateAsc(
                        companyId, fromDate, toDate);
        return holidays.stream()
                .filter(holiday -> !holiday.isOptionalHoliday())
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public Set<LocalDate> mandatoryHolidayDates(Long companyId, YearMonth month) {
        return mandatoryHolidayDates(companyId, month.atDay(1), month.atEndOfMonth());
    }

    @Transactional
    public void delete(Long id) {
        holidayRepository.delete(getById(id));
    }

    private Holiday apply(Holiday holiday, HolidayRequest request) {
        Long companyId = tenantContext.currentCompanyId().orElse(request.getCompanyId());
        holiday.setCompany(companyId == null ? null : companyService.getById(companyId));
        holiday.setHolidayName(request.getHolidayName());
        holiday.setHolidayDate(request.getHolidayDate());
        holiday.setOptionalHoliday(request.isOptionalHoliday());
        holiday.setDescription(request.getDescription());
        return holiday;
    }
}
