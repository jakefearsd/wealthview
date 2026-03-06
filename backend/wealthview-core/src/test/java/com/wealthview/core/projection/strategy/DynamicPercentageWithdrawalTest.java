package com.wealthview.core.projection.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicPercentageWithdrawalTest {

    @Test
    void computeWithdrawal_returnsPercentageOfCurrentBalance() {
        var strategy = new DynamicPercentageWithdrawal(new BigDecimal("0.04"));
        var ctx = new WithdrawalContext(
                new BigDecimal("1000000"),
                new BigDecimal("950000"),
                BigDecimal.ZERO,
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                1);

        var withdrawal = strategy.computeWithdrawal(ctx);

        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("40000"));
    }

    @Test
    void computeWithdrawal_decliningBalance_reducesWithdrawal() {
        var strategy = new DynamicPercentageWithdrawal(new BigDecimal("0.04"));
        var ctx = new WithdrawalContext(
                new BigDecimal("500000"),
                new BigDecimal("480000"),
                new BigDecimal("40000"),
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                5);

        var withdrawal = strategy.computeWithdrawal(ctx);

        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    void computeWithdrawal_growingBalance_increasesWithdrawal() {
        var strategy = new DynamicPercentageWithdrawal(new BigDecimal("0.04"));
        var ctx = new WithdrawalContext(
                new BigDecimal("1500000"),
                new BigDecimal("1400000"),
                new BigDecimal("40000"),
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                3);

        var withdrawal = strategy.computeWithdrawal(ctx);

        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("60000"));
    }
}
