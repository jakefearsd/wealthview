package com.wealthview.core.projection.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VanguardDynamicSpendingWithdrawalTest {

    private final BigDecimal CEILING = new BigDecimal("0.05");
    private final BigDecimal FLOOR = new BigDecimal("-0.025");

    @Test
    void computeWithdrawal_firstYear_returnsBaseRate() {
        var strategy = new VanguardDynamicSpendingWithdrawal(
                new BigDecimal("0.04"), CEILING, FLOOR);
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
    void computeWithdrawal_strongGrowth_capsIncreaseAtCeiling() {
        var strategy = new VanguardDynamicSpendingWithdrawal(
                new BigDecimal("0.04"), CEILING, FLOOR);
        // Previous withdrawal 40000, balance 1300000
        // Raw = 1300000 * 0.04 = 52000
        // Max allowed = 40000 * 1.05 = 42000
        // Capped at 42000
        var ctx = new WithdrawalContext(
                new BigDecimal("1300000"),
                new BigDecimal("1200000"),
                new BigDecimal("40000"),
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                2);

        var withdrawal = strategy.computeWithdrawal(ctx);

        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("42000.0000"));
    }

    @Test
    void computeWithdrawal_decline_floorsDecreaseAtFloor() {
        var strategy = new VanguardDynamicSpendingWithdrawal(
                new BigDecimal("0.04"), CEILING, FLOOR);
        // Previous withdrawal 40000, balance dropped to 700000
        // Raw = 700000 * 0.04 = 28000
        // Min allowed = 40000 * 0.975 = 39000
        // Floored at 39000
        var ctx = new WithdrawalContext(
                new BigDecimal("700000"),
                new BigDecimal("650000"),
                new BigDecimal("40000"),
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                2);

        var withdrawal = strategy.computeWithdrawal(ctx);

        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("39000.0000"));
    }

    @Test
    void computeWithdrawal_moderateGrowth_noCap() {
        var strategy = new VanguardDynamicSpendingWithdrawal(
                new BigDecimal("0.04"), CEILING, FLOOR);
        // Previous withdrawal 40000, balance at 1020000
        // Raw = 1020000 * 0.04 = 40800
        // Max = 42000, Min = 39000 -> within bounds
        var ctx = new WithdrawalContext(
                new BigDecimal("1020000"),
                new BigDecimal("980000"),
                new BigDecimal("40000"),
                new BigDecimal("0.07"),
                new BigDecimal("0.03"),
                2);

        var withdrawal = strategy.computeWithdrawal(ctx);

        assertThat(withdrawal).isEqualByComparingTo(new BigDecimal("40800.0000"));
    }
}
