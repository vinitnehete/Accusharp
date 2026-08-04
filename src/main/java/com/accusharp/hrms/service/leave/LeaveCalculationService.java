package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts leave requests into the day counts payroll and attendance need.
 * A half day is worth 0.5 and is only ever valid on a single-day request, so
 * multi-day ranges always count whole days.
 */
@Service
@RequiredArgsConstructor
public class LeaveCalculationService {

    private static final int SCALE = 1;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final LeaveRequestRepository leaveRequestRepository;

    /** Days a request is worth, half days included. */
    public BigDecimal countDays(LocalDate fromDate, LocalDate toDate, LeaveDuration duration) {
        long days = fromDate.datesUntil(toDate.plusDays(1)).count();
        if (duration != LeaveDuration.FULL_DAY) {
            return HALF.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(days).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Each date in the month that falls inside an approved leave, mapped to how
     * much of that day is leave (1.0 or 0.5) and the type taken.
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, LeaveDay> approvedLeaveDaysInMonth(String userId, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        List<LeaveRequest> approved = leaveRequestRepository
                .findAllByUserIdAndStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                        userId, List.of(LeaveStatus.APPROVED), last, first);

        Map<LocalDate, LeaveDay> byDate = new HashMap<>();
        for (LeaveRequest request : approved) {
            LocalDate start = request.getFromDate().isBefore(first) ? first : request.getFromDate();
            LocalDate end = request.getToDate().isAfter(last) ? last : request.getToDate();
            BigDecimal fraction = request.getDuration().getDayFraction();

            start.datesUntil(end.plusDays(1)).forEach(date ->
                    byDate.put(date, new LeaveDay(request.getLeaveType(), fraction,
                            request.getLeaveType().isPaid())));
        }
        return byDate;
    }

    /**
     * Paid leave days in the month, restricted to the days the employee was
     * actually expected to work - leave on a weekly off or holiday is not
     * consumed and must never offset LOP.
     */
    public BigDecimal paidLeaveDays(Map<LocalDate, LeaveDay> leaveDays, java.util.Set<LocalDate> workingDates) {
        return leaveDays.entrySet().stream()
                .filter(entry -> entry.getValue().paid())
                .filter(entry -> workingDates.contains(entry.getKey()))
                .map(entry -> entry.getValue().fraction())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** All leave days in the month, paid or not, on expected working days. */
    public BigDecimal totalLeaveDays(Map<LocalDate, LeaveDay> leaveDays, java.util.Set<LocalDate> workingDates) {
        return leaveDays.entrySet().stream()
                .filter(entry -> workingDates.contains(entry.getKey()))
                .map(entry -> entry.getValue().fraction())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    public record LeaveDay(LeaveType leaveType, BigDecimal fraction, boolean paid) {
    }
}
