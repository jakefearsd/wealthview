package com.wealthview.core.property;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AmortizationCalculatorTest {

    @Test
    void remainingBalance_day1_equalsLoanAmount() {
        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("300000"), new BigDecimal("0.065"),
                360, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1));

        assertThat(balance.setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("300000.00");
    }

    @ParameterizedTest
    @CsvSource({
            // 30yr @ 6.5% on $300K — computed via B = P * [(1+r)^n - (1+r)^p] / [(1+r)^n - 1]
            "12,  296646.82",
            "60,  280832.93",
            "120, 254328.38",
            "360, 0.00"
    })
    void remainingBalance_30yrAt6pt5_matchesAmortizationTable(int paymentsMade, String expectedBalance) {
        var startDate = LocalDate.of(2024, 1, 1);
        var asOfDate = startDate.plusMonths(paymentsMade);

        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("300000"), new BigDecimal("0.065"),
                360, startDate, asOfDate);

        assertThat(balance.setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo(expectedBalance);
    }

    @Test
    void remainingBalance_zeroInterestRate_simpleLinearPayoff() {
        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("120000"), BigDecimal.ZERO,
                360, LocalDate.of(2024, 1, 1), LocalDate.of(2034, 1, 1));

        // 120 payments out of 360 = 1/3 paid off => 80000 remaining
        assertThat(balance.setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("80000.00");
    }

    @Test
    void remainingBalance_pastTerm_returnsZero() {
        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("300000"), new BigDecimal("0.065"),
                360, LocalDate.of(1990, 1, 1), LocalDate.of(2025, 1, 1));

        assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void remainingBalance_beforeStartDate_returnsLoanAmount() {
        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("300000"), new BigDecimal("0.065"),
                360, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 1, 1));

        assertThat(balance.setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("300000.00");
    }
}
