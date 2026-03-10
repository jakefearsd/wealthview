package com.wealthview.core.projection.tax;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RentalLossCalculatorTest {

    private final RentalLossCalculator calculator = new RentalLossCalculator();

    @Test
    void positiveIncome_noLoss_allPassesThrough() {
        var result = calculator.applyLossRules(
                new BigDecimal("10000"), "rental_passive",
                BigDecimal.ZERO, new BigDecimal("80000"), BigDecimal.ZERO);

        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("0");
        assertThat(result.lossSuspended()).isEqualByComparingTo("0");
        assertThat(result.suspendedLossReleased()).isEqualByComparingTo("0");
        assertThat(result.netTaxableIncome()).isEqualByComparingTo("10000");
    }

    @Test
    void activeREPS_allLossesOffsetAnyIncome() {
        // Net rental income = -15000 (loss)
        var result = calculator.applyLossRules(
                new BigDecimal("-15000"), "rental_active_reps",
                BigDecimal.ZERO, new BigDecimal("100000"), BigDecimal.ZERO);

        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("15000");
        assertThat(result.lossSuspended()).isEqualByComparingTo("0");
        assertThat(result.netTaxableIncome()).isEqualByComparingTo("-15000");
    }

    @Test
    void activeSTR_allLossesOffsetAnyIncome() {
        var result = calculator.applyLossRules(
                new BigDecimal("-20000"), "rental_active_str",
                BigDecimal.ZERO, new BigDecimal("150000"), BigDecimal.ZERO);

        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("20000");
        assertThat(result.lossSuspended()).isEqualByComparingTo("0");
        assertThat(result.netTaxableIncome()).isEqualByComparingTo("-20000");
    }

    @Test
    void passive_lowMAGI_25kException() {
        // Net rental income = -30000 (loss), MAGI = $80000 (below $100k)
        var result = calculator.applyLossRules(
                new BigDecimal("-30000"), "rental_passive",
                BigDecimal.ZERO, new BigDecimal("80000"), BigDecimal.ZERO);

        // Full $25k exception applies → $25k deductible, $5k suspended
        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("25000");
        assertThat(result.lossSuspended()).isEqualByComparingTo("5000");
        assertThat(result.netTaxableIncome()).isEqualByComparingTo("-25000");
    }

    @Test
    void passive_magiPhaseout_reducedException() {
        // MAGI = $120000 → exception reduced by 50% of (120000 - 100000) = $10000
        // Exception = 25000 - 10000 = 15000
        var result = calculator.applyLossRules(
                new BigDecimal("-30000"), "rental_passive",
                BigDecimal.ZERO, new BigDecimal("120000"), BigDecimal.ZERO);

        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("15000");
        assertThat(result.lossSuspended()).isEqualByComparingTo("15000");
    }

    @Test
    void passive_magiAbove150k_noException() {
        // MAGI = $160000 → exception fully phased out
        var result = calculator.applyLossRules(
                new BigDecimal("-20000"), "rental_passive",
                BigDecimal.ZERO, new BigDecimal("160000"), BigDecimal.ZERO);

        // Losses can only offset other passive income (which is $0)
        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("0");
        assertThat(result.lossSuspended()).isEqualByComparingTo("20000");
    }

    @Test
    void passive_withOtherPassiveIncome_offsetsFirst() {
        // Net rental loss = -20000, other passive income = $8000, MAGI = $160000 (no exception)
        var result = calculator.applyLossRules(
                new BigDecimal("-20000"), "rental_passive",
                new BigDecimal("8000"), new BigDecimal("160000"), BigDecimal.ZERO);

        // $8000 offset by passive income, remaining $12000 suspended
        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("8000");
        assertThat(result.lossSuspended()).isEqualByComparingTo("12000");
    }

    @Test
    void passive_withPriorSuspendedLosses_releasedAgainstIncome() {
        // Net rental income = $10000 (profit), prior suspended = $15000
        var result = calculator.applyLossRules(
                new BigDecimal("10000"), "rental_passive",
                BigDecimal.ZERO, new BigDecimal("100000"), new BigDecimal("15000"));

        // Income $10000 offset by $10000 of prior suspended losses
        assertThat(result.suspendedLossReleased()).isEqualByComparingTo("10000");
        assertThat(result.netTaxableIncome()).isEqualByComparingTo("0");
    }

    @Test
    void passive_lossWithPriorSuspended_addsTogether() {
        // Net rental loss = -5000, MAGI = $160000 (no exception), prior suspended = $10000
        var result = calculator.applyLossRules(
                new BigDecimal("-5000"), "rental_passive",
                BigDecimal.ZERO, new BigDecimal("160000"), new BigDecimal("10000"));

        // No exception, no passive income → all losses suspended
        // Current loss $5000 + prior $10000 = $15000 total suspended
        assertThat(result.lossSuspended()).isEqualByComparingTo("15000");
        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("0");
    }

    @Test
    void passive_lossSmallerThanException_fullDeduction() {
        // Net rental loss = -10000, MAGI = $80000 → full $25k exception
        var result = calculator.applyLossRules(
                new BigDecimal("-10000"), "rental_passive",
                BigDecimal.ZERO, new BigDecimal("80000"), BigDecimal.ZERO);

        assertThat(result.lossAppliedToIncome()).isEqualByComparingTo("10000");
        assertThat(result.lossSuspended()).isEqualByComparingTo("0");
    }
}
