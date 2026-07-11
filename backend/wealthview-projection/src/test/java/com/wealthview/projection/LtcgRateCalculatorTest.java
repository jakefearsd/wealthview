package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Verifies {@link LtcgRateCalculator} derives the per-year marginal LTCG rate from each year's
 * expected ordinary income against the single-filer 2025 brackets (0% ≤ $48,350, then 15%).
 */
class LtcgRateCalculatorTest {

    private static CapitalGainsTaxCalculator capitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    @Test
    void compute_noCalculator_returnsAllZeros() {
        double[] rates = LtcgRateCalculator.compute(
                null, new double[]{100_000, 100_000}, 2025, 2, FilingStatus.SINGLE, 0.0);

        assertThat(rates).containsExactly(0.0, 0.0);
    }

    @Test
    void compute_lowIncomeYear_zeroRate() {
        // Ordinary 20k + a small probe gain stays inside the single 2025 0% LTCG bracket (≤ $48,350).
        double[] rates = LtcgRateCalculator.compute(
                capitalGainsCalc(), new double[]{20_000}, 2025, 1, FilingStatus.SINGLE, 0.0);

        assertThat(rates[0]).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void compute_highIncomeYear_fifteenPercentRate() {
        // Ordinary 100k stacks the probe gain into the 15% bracket ($48,350..$533,400);
        // MAGI stays below the $200k single NIIT threshold, so no surtax applies.
        double[] rates = LtcgRateCalculator.compute(
                capitalGainsCalc(), new double[]{100_000}, 2025, 1, FilingStatus.SINGLE, 0.0);

        assertThat(rates[0]).isEqualTo(0.15, within(1e-9));
    }
}
