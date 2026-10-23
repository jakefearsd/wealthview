package com.wealthview.projection;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentSizingReason;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link BondTentSizer}: present-value target, mix real yield, and the rule-of-thumb explanation.
 */
class BondTentSizerTest {

    private static final double PORTFOLIO = 1_000_000.0;

    @Test
    void target_fiveYearsFlat40kAt1Point5Percent_matchesPresentValue() {
        var withdrawals = flat(40_000.0, 5);

        var target = BondTentSizer.target(withdrawals, 5.0, 0.015);

        assertThat(target).isCloseTo(191_305.7989, within(1e-3));
    }

    @Test
    void target_twoAndAHalfYears_countsPartialYearAtHalf() {
        var withdrawals = flat(40_000.0, 5);

        var target = BondTentSizer.target(withdrawals, 2.5, 0.015);

        assertThat(target).isCloseTo(97_361.6768, within(1e-3));
    }

    @Test
    void target_zeroYield_isUndiscountedSum() {
        var withdrawals = flat(40_000.0, 5);

        var target = BondTentSizer.target(withdrawals, 2.5, 0.0);

        assertThat(target).isCloseTo(100_000.0, within(1e-6));
    }

    @Test
    void target_horizonShorterThanCoverage_usesAvailableYears() {
        var withdrawals = new double[] {40_000.0, 30_000.0};

        var target = BondTentSizer.target(withdrawals, 5.0, 0.0);

        assertThat(target).isCloseTo(70_000.0, within(1e-6));
    }

    @Test
    void target_negativeRealYield_exceedsWithdrawals() {
        var withdrawals = new double[] {10_000.0};

        var target = BondTentSizer.target(withdrawals, 1.0, -0.01);

        assertThat(target).isCloseTo(10_101.0101, within(1e-3));
    }

    @Test
    void mixRealYield_balancedMixAtTwoAndAHalfPercentInflation_isWeightedRealYield() {
        var yields = new BondMaturityValues(bd("0.0394"), bd("0.0465"), bd("0.048"), bd("0.0539"));
        var shares = new BondMaturityValues(bd("0.2"), bd("0.3"), bd("0.5"), bd("0"));

        var realYield = BondTentSizer.mixRealYield(yields, shares, 0.025);

        assertThat(realYield).isCloseTo(0.0203219512, within(1e-9));
    }

    @Test
    void mixRealYield_nullShare_treatedAsZero() {
        var yields = new BondMaturityValues(bd("0.0394"), bd("0.0465"), bd("0.048"), bd("0.0539"));
        var shares = new BondMaturityValues(bd("1"), null, null, null);

        var realYield = BondTentSizer.mixRealYield(yields, shares, 0.025);

        assertThat(realYield).isCloseTo(1.0394 / 1.025 - 1, within(1e-9));
    }

    @Test
    void size_ruleOfThumbsFallOutOfTheMath() {
        var spending = flat(40_000.0, 10);
        var zeros = flat(0.0, 10);

        var twoAndAHalf = BondTentSizer.size(spending, zeros, zeros, 2.5, 0.015, PORTFOLIO);
        var six = BondTentSizer.size(spending, zeros, zeros, 6.0, 0.015, PORTFOLIO);

        assertThat(twoAndAHalf.target() / PORTFOLIO).isBetween(0.09, 0.11);
        assertThat(six.target() / PORTFOLIO).isBetween(0.18, 0.25);
    }

    @Test
    void size_spendingAboveFourPercent_reasonIsSpendingLevel() {
        var spending = flat(60_000.0, 5);
        var income = new double[] {0.0, 0.0, 0.0, 30_000.0, 30_000.0};
        var tax = flat(5_000.0, 5);

        var sizing = BondTentSizer.size(spending, income, tax, 5.0, 0.02, PORTFOLIO);

        assertThat(sizing.target()).isCloseTo(251_487.581, within(1e-2));
        assertThat(sizing.ruleOfThumb()).isCloseTo(200_000.0, within(1e-6));
        assertThat(sizing.reason()).isEqualTo(BondTentSizingReason.SPENDING_LEVEL);
        assertThat(sizing.reasonAmount()).isCloseTo(100_000.0, within(1e-6));
    }

    @Test
    void size_incomeCoversMost_reasonIsIncome() {
        var spending = flat(40_000.0, 5);
        var income = flat(30_000.0, 5);
        var zeros = flat(0.0, 5);

        var sizing = BondTentSizer.size(spending, income, zeros, 5.0, 0.0, PORTFOLIO);

        assertThat(sizing.reason()).isEqualTo(BondTentSizingReason.INCOME);
        assertThat(sizing.reasonAmount()).isCloseTo(-150_000.0, within(1e-6));
    }

    @Test
    void size_taxDominates_reasonIsWithdrawalTax() {
        var spending = flat(40_000.0, 5);
        var tax = flat(10_000.0, 5);
        var zeros = flat(0.0, 5);

        var sizing = BondTentSizer.size(spending, zeros, tax, 5.0, 0.0, PORTFOLIO);

        assertThat(sizing.reason()).isEqualTo(BondTentSizingReason.WITHDRAWAL_TAX);
        assertThat(sizing.reasonAmount()).isCloseTo(50_000.0, within(1e-6));
    }

    @Test
    void size_highYieldNothingElse_reasonIsMixYields() {
        var spending = flat(40_000.0, 5);
        var zeros = flat(0.0, 5);

        var sizing = BondTentSizer.size(spending, zeros, zeros, 5.0, 0.03, PORTFOLIO);

        assertThat(sizing.reason()).isEqualTo(BondTentSizingReason.MIX_YIELDS);
        assertThat(sizing.reasonAmount()).isNegative();
    }

    @Test
    void size_incomeAboveSpending_clampsWithdrawalAtZero() {
        var spending = flat(20_000.0, 5);
        var income = flat(30_000.0, 5);
        var zeros = flat(0.0, 5);

        var sizing = BondTentSizer.size(spending, income, zeros, 5.0, 0.0, PORTFOLIO);

        assertThat(sizing.withdrawals()).containsOnly(0.0);
        assertThat(sizing.target()).isZero();
    }

    @Test
    void size_incomeAboveSpendingClamped_tiesSpendingLevelAndIncomeAndPicksSpendingLevel() {
        var spending = flat(20_000.0, 5);
        var income = flat(30_000.0, 5);
        var zeros = flat(0.0, 5);

        var sizing = BondTentSizer.size(spending, income, zeros, 5.0, 0.0, PORTFOLIO);

        assertThat(sizing.target()).isZero();
        assertThat(sizing.ruleOfThumb()).isCloseTo(200_000.0, within(1e-6));
        assertThat(sizing.reason()).isEqualTo(BondTentSizingReason.SPENDING_LEVEL);
        assertThat(sizing.reasonAmount()).isCloseTo(-100_000.0, within(1e-6));
    }

    private static double[] flat(double value, int years) {
        var values = new double[years];
        Arrays.fill(values, value);
        return values;
    }
}
