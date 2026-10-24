package com.wealthview.projection;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrialTentSizerTest {

    private static TrialTentPlan plan(double coverage, double realYield) {
        return new TrialTentPlan(coverage, realYield, new double[] {1, 0, 0, 0}, new double[4],
                List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH), 0, 0, 0, null);
    }

    private static double[] filled(int n, double value) {
        double[] a = new double[n];
        Arrays.fill(a, value);
        return a;
    }

    private static TrialTentSizer.Inputs inputs(OrdinaryTaxTable[] tables, double taxable, double traditional,
                                                WithdrawalOrder order) {
        return new TrialTentSizer.Inputs(tables, tables == null ? null : new double[10], null, null, null,
                taxable, traditional, 0, order, 65, 60);
    }

    private static double target(TrialTentPlan plan, TrialTentSizer.Inputs in, double floor, double income,
                                 double surplusTax) {
        return TrialTentSizer.target(plan, in, filled(10, floor), new double[10], filled(10, income),
                filled(10, surplusTax));
    }

    private static OrdinaryTaxTable[] flatTables(double rate) {
        var tables = new OrdinaryTaxTable[10];
        Arrays.fill(tables, OrdinaryTaxTable.flat(rate));
        return tables;
    }

    @Test
    void target_noTaxTables_isPresentValueOfTheNeed() {
        var in = inputs(null, 1e9, 0, WithdrawalOrder.TAXABLE_FIRST);

        assertThat(target(plan(5, 0.015), in, 40_000, 0, 0)).isEqualTo(191_305.7989, within(1e-3));
    }

    @Test
    void target_twoAndAHalfYears_countsTheHalfYear() {
        var in = inputs(null, 1e9, 0, WithdrawalOrder.TAXABLE_FIRST);

        assertThat(target(plan(2.5, 0.015), in, 40_000, 0, 0)).isEqualTo(97_361.6768, within(1e-3));
    }

    @Test
    void target_incomeAboveSpending_isZero() {
        var in = inputs(null, 1e9, 0, WithdrawalOrder.TAXABLE_FIRST);

        assertThat(target(plan(5, 0.015), in, 40_000, 50_000, 0)).isZero();
    }

    @Test
    void target_baseIncomeTaxJoinsTheWithdrawal() {
        var in = inputs(null, 1e9, 0, WithdrawalOrder.TAXABLE_FIRST);

        assertThat(target(plan(5, 0), in, 40_000, 30_000, 5_000)).isEqualTo(75_000.0, within(1e-6));
    }

    @Test
    void target_traditionalFirst_addsOrdinaryTaxOnTheDraw() {
        var in = inputs(flatTables(0.20), 0, 1_000_000, WithdrawalOrder.TRADITIONAL_FIRST);

        assertThat(target(plan(5, 0), in, 40_000, 0, 0)).isEqualTo(240_000.0, within(1e-6));
    }

    @Test
    void target_taxableFirstFromTaxable_addsNoOrdinaryTax() {
        var in = inputs(flatTables(0.20), 1_000_000, 0, WithdrawalOrder.TAXABLE_FIRST);

        assertThat(target(plan(5, 0), in, 40_000, 0, 0)).isEqualTo(200_000.0, within(1e-6));
    }

    @Test
    void target_beforeEarlyAccessWithConversions_drawsTaxableAndPricesTheConversion() {
        var in = new TrialTentSizer.Inputs(flatTables(0.20), new double[10], filled(10, 10_000), null, null,
                1_000_000, 1_000_000, 0, WithdrawalOrder.TRADITIONAL_FIRST, 55, 60);

        assertThat(target(plan(5, 0), in, 40_000, 0, 0)).isEqualTo(210_000.0, within(1e-6));
    }
}
