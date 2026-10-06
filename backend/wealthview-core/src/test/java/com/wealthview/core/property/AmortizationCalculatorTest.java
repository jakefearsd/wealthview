package com.wealthview.core.property;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AmortizationCalculatorTest {

    private static final BigDecimal LOAN = new BigDecimal("300000");
    private static final BigDecimal RATE = new BigDecimal("0.065");
    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    private static final BigDecimal CENT = new BigDecimal("0.01");

    private static BigDecimal payment() {
        return AmortizationCalculator.monthlyPayment(LOAN, RATE, 360);
    }

    private static BigDecimal balanceAfter(int payments) {
        return AmortizationCalculator.remainingBalance(LOAN, RATE, 360, START, START.plusMonths(payments));
    }

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
    void monthlyPayment_standardLoan_matchesExpected() {
        var payment = AmortizationCalculator.monthlyPayment(
                new BigDecimal("300000"), new BigDecimal("0.065"), 360);

        // Standard 30yr @ 6.5% on $300K => ~1896.20
        assertThat(payment.setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("1896.20");
    }

    @Test
    void monthlyPayment_zeroRate_simpleDivision() {
        var payment = AmortizationCalculator.monthlyPayment(
                new BigDecimal("120000"), BigDecimal.ZERO, 360);

        // 120000 / 360 = 333.3333
        assertThat(payment.setScale(4, RoundingMode.HALF_UP))
                .isEqualByComparingTo("333.3333");
    }

    @Test
    void monthlyPayment_nullInputs_returnsNull() {
        assertThat(AmortizationCalculator.monthlyPayment(null, new BigDecimal("0.065"), 360)).isNull();
        assertThat(AmortizationCalculator.monthlyPayment(new BigDecimal("300000"), null, 360)).isNull();
    }

    @Test
    void remainingBalance_oneMonthIn_isBelowLoanAmount() {
        // Boundary just above zero: after exactly one payment the balance must
        // have dropped — a mutant flipping monthsBetween<=0 to <0 would return
        // the full loan amount here instead.
        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("300000"), new BigDecimal("0.065"),
                360, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1));

        assertThat(balance).isLessThan(new BigDecimal("300000"));
        assertThat(balance).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void remainingBalance_oneMonthBeforeFinalPayment_isPositiveNotZero() {
        // Boundary just below the term: at term-1 payments the balance must
        // still be positive — a mutant flipping paymentsMade>=term to > would
        // be fine, but flipping to >= term-1 (off-by-one) would wrongly zero it.
        var startDate = LocalDate.of(2024, 1, 1);
        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("300000"), new BigDecimal("0.065"),
                360, startDate, startDate.plusMonths(359));

        assertThat(balance).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void remainingBalance_beforeStartDate_returnsLoanAmount() {
        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("300000"), new BigDecimal("0.065"),
                360, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 1, 1));

        assertThat(balance.setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("300000.00");
    }

    @ParameterizedTest
    @CsvSource({"0, 0.05", "0, 0", "-12, 0.05"})
    void monthlyPayment_nonPositiveTerm_returnsZeroInsteadOfDividingByZero(int termMonths, String rate) {
        var payment = AmortizationCalculator.monthlyPayment(
                new BigDecimal("80000"), new BigDecimal(rate), termMonths);

        assertThat(payment).isEqualByComparingTo("0");
    }

    @Test
    void remainingBalance_zeroTerm_returnsZeroInsteadOfDividingByZero() {
        var balance = AmortizationCalculator.remainingBalance(
                new BigDecimal("80000"), BigDecimal.ZERO,
                0, LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1));

        assertThat(balance).isEqualByComparingTo("0");
    }

    // ── debt service per year / per payment range ─────────────────────────

    @Test
    void debtServiceForPayments_firstTwelve_interestIsRateTimesEachOpeningBalance() {
        var debt = AmortizationCalculator.debtServiceForPayments(LOAN, RATE, 360, 1, 12);

        var monthlyRate = RATE.divide(new BigDecimal("12"), MathContext.DECIMAL128);
        var expectedInterest = BigDecimal.ZERO;
        for (int k = 1; k <= 12; k++) {
            expectedInterest = expectedInterest.add(monthlyRate.multiply(balanceAfter(k - 1)));
        }
        assertThat(debt.interest()).isCloseTo(expectedInterest, within(CENT));
        assertThat(debt.total()).isEqualByComparingTo(payment().multiply(new BigDecimal("12")));
        // Principal repaid is the drop in balance over those payments.
        assertThat(debt.principal()).isCloseTo(LOAN.subtract(balanceAfter(12)),
                within(CENT));
    }

    @Test
    void debtServiceForPayments_rangePastTerm_isClampedToTheFinalPayments() {
        var debt = AmortizationCalculator.debtServiceForPayments(LOAN, RATE, 360, 358, 369);

        assertThat(debt.total()).isEqualByComparingTo(payment().multiply(new BigDecimal("3")));
    }

    @Test
    void debtServiceForYear_fullYear_chargesTwelvePayments() {
        var debt = AmortizationCalculator.debtServiceForYear(LOAN, RATE, 360, START, 2026);

        // Payments 24..35 fall in 2026 (payment k lands k months after the January 2024 start).
        var expected = AmortizationCalculator.debtServiceForPayments(LOAN, RATE, 360, 24, 35);
        assertThat(debt).isEqualTo(expected);
        assertThat(debt.total()).isEqualByComparingTo(payment().multiply(new BigDecimal("12")));
    }

    @Test
    void debtServiceForYear_laterYear_chargesLessInterestAndMorePrincipal() {
        var early = AmortizationCalculator.debtServiceForYear(LOAN, RATE, 360, START, 2026);
        var late = AmortizationCalculator.debtServiceForYear(LOAN, RATE, 360, START, 2046);

        assertThat(late.interest()).isLessThan(early.interest());
        assertThat(late.principal()).isGreaterThan(early.principal());
    }

    @Test
    void debtServiceForYear_startYear_countsOnlyPaymentsAfterTheStartMonth() {
        var debt = AmortizationCalculator.debtServiceForYear(LOAN, RATE, 360, START, 2024);

        // February through December 2024.
        assertThat(debt.total()).isEqualByComparingTo(payment().multiply(new BigDecimal("11")));
    }

    @Test
    void debtServiceForYear_payoffYear_countsOnlyPaymentsUpToPayoff() {
        var debt = AmortizationCalculator.debtServiceForYear(LOAN, RATE, 360, START, 2054);

        // The 360th payment lands in January 2054.
        assertThat(debt.total()).isEqualByComparingTo(payment());
    }

    @ParameterizedTest
    @CsvSource({"2023", "2055", "2070"})
    void debtServiceForYear_beforeFirstPaymentOrAfterPayoff_isZero(int year) {
        var debt = AmortizationCalculator.debtServiceForYear(LOAN, RATE, 360, START, year);

        assertThat(debt.total()).isEqualByComparingTo("0");
    }

    @Test
    void debtServiceForYear_zeroRate_isAllPrincipal() {
        var debt = AmortizationCalculator.debtServiceForYear(
                new BigDecimal("120000"), BigDecimal.ZERO, 120, START, 2026);

        assertThat(debt.interest()).isEqualByComparingTo("0");
        assertThat(debt.principal()).isEqualByComparingTo("12000");
    }

    @Test
    void debtServiceForYear_nonPositiveTerm_isZero() {
        var debt = AmortizationCalculator.debtServiceForYear(LOAN, RATE, 0, START, 2026);

        assertThat(debt.total()).isEqualByComparingTo("0");
    }
}
