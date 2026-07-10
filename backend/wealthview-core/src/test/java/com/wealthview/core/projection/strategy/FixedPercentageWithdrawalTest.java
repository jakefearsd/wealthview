package com.wealthview.core.projection.strategy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedPercentageWithdrawalTest {

    @Test
    void computeWithdrawal_firstYear_returnsRateTimesStartOfYearBalance() {
        var strategy = new FixedPercentageWithdrawal(new BigDecimal("0.04"));
        var ctx = new WithdrawalContext(
                new BigDecimal("1050000"),
                new BigDecimal("1000000"),
                BigDecimal.ZERO,
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                1);

        var withdrawal = strategy.computeWithdrawal(ctx);

        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("40000"));
    }

    @Test
    void computeWithdrawal_secondYear_holdsPreviousWithdrawalConstantReal() {
        var strategy = new FixedPercentageWithdrawal(new BigDecimal("0.04"));
        var ctx = new WithdrawalContext(
                new BigDecimal("1050000"),
                new BigDecimal("1000000"),
                new BigDecimal("40000"),
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                2);

        var withdrawal = strategy.computeWithdrawal(ctx);

        // Real terms: constant real, no nominal escalation → previous withdrawal unchanged.
        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("40000.0000"));
    }

    @Test
    void computeWithdrawal_thirdYear_staysConstantRealIgnoringInflation() {
        var strategy = new FixedPercentageWithdrawal(new BigDecimal("0.04"));
        var ctx = new WithdrawalContext(
                new BigDecimal("1000000"),
                new BigDecimal("950000"),
                new BigDecimal("41200"),
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                3);

        var withdrawal = strategy.computeWithdrawal(ctx);

        // Real terms: previous withdrawal carried forward unchanged regardless of inflation.
        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("41200.0000"));
    }
}
