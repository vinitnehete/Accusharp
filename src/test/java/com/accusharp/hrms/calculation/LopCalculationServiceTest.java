package com.accusharp.hrms.calculation;

import com.accusharp.hrms.service.calculation.LopCalculationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LopCalculationServiceTest {

    private final LopCalculationService service = new LopCalculationService();

    @Test
    @DisplayName("the worked example from the specification: 26 - 23 - 2 = 1")
    void calculatesTheSpecificationExample() {
        BigDecimal lop = service.calculateLopDays(
                new BigDecimal("26"), new BigDecimal("23"), new BigDecimal("2"));

        assertThat(lop).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("full attendance leaves no loss of pay")
    void noLopWhenFullyPresent() {
        assertThat(service.calculateLopDays(new BigDecimal("26"), new BigDecimal("26"), BigDecimal.ZERO))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("half days carry through as half a day of attendance")
    void handlesHalfDays() {
        BigDecimal lop = service.calculateLopDays(
                new BigDecimal("26"), new BigDecimal("24.5"), new BigDecimal("1"));

        assertThat(lop).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("leave beyond the working days never produces negative LOP")
    void clampsAtZero() {
        assertThat(service.calculateLopDays(new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("5")))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("payable days are working days minus loss of pay")
    void calculatesPayableDays() {
        assertThat(service.calculatePayableDays(new BigDecimal("26"), new BigDecimal("1")))
                .isEqualByComparingTo("25");
    }
}
