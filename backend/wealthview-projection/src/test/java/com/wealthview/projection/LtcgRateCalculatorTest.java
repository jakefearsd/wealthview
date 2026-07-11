package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Verifies {@link LtcgRateCalculator} derives the per-year marginal LTCG rate from each year's
 * expected ordinary income, NETTED by the standard deduction (cross-engine parity with
 * {@code PoolStrategy.MultiPool#computeLtcgTax}), against the single-filer 2025 brackets
 * (0% ≤ $48,350, then 15%) and the $15,000 single 2025 standard deduction.
 */
class LtcgRateCalculatorTest {

    private static CapitalGainsTaxCalculator capitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    /** Single-filer 2025 fixtures: $15,000 standard deduction, used to net the LTCG stacking floor. */
    private static FederalTaxCalculator federalTaxCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    @Test
    void compute_noCalculator_returnsAllZeros() {
        double[] rates = LtcgRateCalculator.compute(
                null, null, new double[]{100_000, 100_000}, 2025, 2, FilingStatus.SINGLE, 0.0);

        assertThat(rates).containsExactly(0.0, 0.0);
    }

    @Test
    void compute_lowIncomeYear_zeroRate() {
        // Ordinary 20k - 15k deduction = 5k net; + 1k probe = 6k, well inside the single 2025
        // 0% LTCG bracket (<= $48,350).
        double[] rates = LtcgRateCalculator.compute(
                capitalGainsCalc(), federalTaxCalc(), new double[]{20_000}, 2025, 1,
                FilingStatus.SINGLE, 0.0);

        assertThat(rates[0]).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void compute_deductionNettedIncome_zeroRateInsteadOfFifteen() {
        // Cross-engine parity fix: ordinary 50k - 15k standard deduction = 35k net; + 1k probe =
        // 36k, which stays under the $48,350 0%/15% ceiling -> 0%. Pre-fix, the floor stacked the
        // probe on GROSS ordinary income (50k + 1k = 51k > $48,350), landing in the 15% bracket --
        // the exact bug the deterministic engine's Task-5 fix closed for PoolStrategy.MultiPool.
        double[] rates = LtcgRateCalculator.compute(
                capitalGainsCalc(), federalTaxCalc(), new double[]{50_000}, 2025, 1,
                FilingStatus.SINGLE, 0.0);

        assertThat(rates[0]).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void compute_highIncomeYear_fifteenPercentRate() {
        // Ordinary 100k - 15k deduction = 85k net; + 1k probe = 86k, inside the 15% bracket
        // ($48,350..$533,400); MAGI (gross 100k + probe = 101k) stays below the $200k single NIIT
        // threshold, so no surtax applies.
        double[] rates = LtcgRateCalculator.compute(
                capitalGainsCalc(), federalTaxCalc(), new double[]{100_000}, 2025, 1,
                FilingStatus.SINGLE, 0.0);

        assertThat(rates[0]).isEqualTo(0.15, within(1e-9));
    }

    @Test
    void compute_noFederalTaxCalculator_fallsBackToGrossStacking() {
        // No deduction source wired (mirrors PoolStrategy.MultiPool#resolveOrdinaryDeduction's ZERO
        // fallback): the floor stays gross. Ordinary 50k + 1k probe = 51k > $48,350 -> 15%.
        double[] rates = LtcgRateCalculator.compute(
                capitalGainsCalc(), null, new double[]{50_000}, 2025, 1, FilingStatus.SINGLE, 0.0);

        assertThat(rates[0]).isEqualTo(0.15, within(1e-9));
    }
}
