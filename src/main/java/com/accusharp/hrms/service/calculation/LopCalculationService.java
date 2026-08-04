package com.accusharp.hrms.service.calculation;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Loss of pay. Never entered by hand - always derived:
 *
 * <pre>
 *   LOP days = working days - present days - approved paid leave
 * </pre>
 *
 * Working days already exclude weekly offs and company holidays, so those
 * never turn into LOP.
 */
@Service
public class LopCalculationService {

    private static final int SCALE = 1;

    /**
     * @param workingDays  days the employee was expected to work
     * @param presentDays  attended days (half days count as 0.5)
     * @param paidLeaveDays approved leave of a paid type
     * @return LOP days, never negative
     */
    public BigDecimal calculateLopDays(BigDecimal workingDays, BigDecimal presentDays, BigDecimal paidLeaveDays) {
        BigDecimal lop = nullSafe(workingDays)
                .subtract(nullSafe(presentDays))
                .subtract(nullSafe(paidLeaveDays));
        return lop.max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Days that are actually paid: working days minus LOP, never negative. */
    public BigDecimal calculatePayableDays(BigDecimal workingDays, BigDecimal lopDays) {
        return nullSafe(workingDays).subtract(nullSafe(lopDays))
                .max(BigDecimal.ZERO)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
