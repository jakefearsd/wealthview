package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrialPoolsTest {

    private static final double EPS = 1e-6;

    private static TrialPools millionAtCost() {
        var lots = new TaxableLots();
        lots.addLot(1_000_000, 1_000_000);
        return new TrialPools(new double[] {1_000_000, 0, 0, 0, 0}, lots);
    }

    @Test
    void growTaxable_withReservation_splitsYieldOverUnreservedPartAndAddsSleeveInterest() {
        var tp = millionAtCost();

        var split = tp.growTaxable(0.05, 0.02, 0.04, 0.6, 100_000, 3_000);

        assertThat(tp.taxable()).isCloseTo(1_050_000, within(EPS));
        assertThat(split.dividendIncome()).isCloseTo(10_800, within(EPS));
        assertThat(split.interestIncome()).isCloseTo(17_400, within(EPS));
    }

    @Test
    void growTaxable_noReservation_matchesTheExistingSplit() {
        var expected = millionAtCost();
        var actual = millionAtCost();

        var expectedSplit = expected.growTaxable(0.05, 0.02, 0.04, 0.6);
        var actualSplit = actual.growTaxable(0.05, 0.02, 0.04, 0.6, 0, 0);

        assertThat(actualSplit).isEqualTo(expectedSplit);
        assertThat(actual.taxable()).isEqualTo(expected.taxable());
    }

    @Test
    void growTaxable_noYieldsAtAll_growsPoolWithNoIncomeAndNoNewBasis() {
        var lots = new TaxableLots();
        lots.addLot(1_000_000, 1_000_000);
        var tp = new TrialPools(new double[] {1_000_000, 0, 0, 0, 0}, lots);

        var split = tp.growTaxable(0.05, 0.0, 0.0, 0.6);

        assertThat(tp.taxable()).isCloseTo(1_050_000, within(EPS));
        assertThat(split.dividendIncome()).isZero();
        assertThat(split.interestIncome()).isZero();
        assertThat(lots.totalValue()).isCloseTo(1_050_000, within(EPS));
        assertThat(lots.totalBasis()).isCloseTo(1_000_000, within(EPS));
    }
}
