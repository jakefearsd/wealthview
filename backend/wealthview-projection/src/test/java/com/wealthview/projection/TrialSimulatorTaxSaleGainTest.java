package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** D5 (Phase 1a), Monte Carlo side: non-spending taxable sales accumulate their realized gain on
 * {@link TrialPools}; {@link TrialSimulator#ltcgTaxForYear} taxes it with the year's LTCG and prices
 * the LTCG bill's own funding sale. */
class TrialSimulatorTaxSaleGainTest {

    /** taxable 200,000 at 50% embedded gain; traditional 500,000; no Roth. */
    private static TrialPools gainPools() {
        var lots = new TaxableLots();
        lots.addLot(100_000, 200_000);
        return new TrialPools(new double[]{200_000, 500_000, 0, 0, 0}, lots);
    }

    private static TrialPools lossPools() {
        var lots = new TaxableLots();
        lots.addLot(300_000, 200_000);
        return new TrialPools(new double[]{200_000, 500_000, 0, 0, 0}, lots);
    }

    @Test
    void deductTaxFromPools_gainLots_accumulatesTaxSaleGain() {
        var tp = gainPools();

        tp.deductTaxFromPools(10_000);

        assertThat(tp.drainTaxSaleGain()).isEqualTo(5_000.0, within(1e-6));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void sellTaxable_spendingSale_doesNotAccumulate() {
        var tp = gainPools();

        double gain = tp.sellTaxable(10_000);

        assertThat(gain).isEqualTo(5_000.0, within(1e-6));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void sellTaxableForTaxAndRefill_accumulate() {
        var tp = gainPools();

        tp.sellTaxableForTax(4_000);
        tp.debitTaxableWithTraditionalSpillover(6_000);

        assertThat(tp.drainTaxSaleGain()).isEqualTo(5_000.0, within(1e-6));
    }

    @Test
    void ltcgTaxForYear_flatFifteen_includesAccumulatedGainAndOwnFundingSale() {
        // realized 8,000 + accumulated tax-sale gain 2,000 = 10,000 LTCG -> 1,500 at a flat 15%.
        // The 1,500 bill sells 50%-gain lots: T = 0.15 * (10,000 + 0.5 T) -> T = 1500 / 0.925.
        var tp = gainPools();
        tp.sellTaxableForTax(4_000);   // accumulates 2,000 of gain

        double tax = TrialSimulator.ltcgTaxForYear(tp, 8_000, 0, LtcgTaxTable.flat(0.15), 0, 0);

        assertThat(tax).isEqualTo(1500.0 / 0.925, within(0.01));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void ltcgTaxForYear_accumulatedLossExceedsGain_zeroTax() {
        var tp = lossPools();
        tp.sellTaxableForTax(40_000);  // accumulates -20,000

        double tax = TrialSimulator.ltcgTaxForYear(tp, 8_000, 0, LtcgTaxTable.flat(0.15), 0, 0);

        assertThat(tax).isEqualTo(0.0, within(1e-12));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void ltcgTaxForYear_nullTable_zeroTaxAndAccumulatorDrained() {
        var tp = gainPools();
        tp.sellTaxableForTax(4_000);

        double tax = TrialSimulator.ltcgTaxForYear(tp, 8_000, 0, null, 0, 0);

        assertThat(tax).isEqualTo(0.0, within(1e-12));
        assertThat(tp.drainTaxSaleGain()).isEqualTo(0.0, within(1e-12));
    }

    /** One retired year, no growth/returns: a 40,000 spend drawn from traditional, taxed 20% ordinary
     * (8,000 bill, paid from taxable first), flat 15% LTCG. Taxable holds 200,000 with {@code basis}. */
    private static double finalBalanceOfTaxFundedFromTaxable(double basis) {
        var config = TrialSimulator.SimulationConfig.builder(200_000.0, 500_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20)}, new double[]{0.0})
                .retirementAge(62)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .taxableBasis(basis)
                .ltcgTaxTableByYear(new LtcgTaxTable[]{LtcgTaxTable.flat(0.15)})
                .build();

        return new TrialSimulator().simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{40_000}, new double[]{0}, 1, config)
                .finalBalance();
    }

    @Test
    void simulateTrial_withdrawalTaxPaidFromGainLots_saleGainTaxedAsLtcg() {
        // 40,000 traditional draw -> 8,000 ordinary bill sold from 50%-gain taxable lots = 4,000 gain.
        // LTCG 15% on it, plus its own funding sale: T = 600 / (1 - 0.15 * 0.5) = 648.65.
        // Final = 700,000 - 40,000 - 8,000 - 648.65 = 651,351.35 (was 652,000 before D5).
        double finalBalance = finalBalanceOfTaxFundedFromTaxable(100_000.0);

        assertThat(finalBalance).isEqualTo(652_000.0 - 600.0 / 0.925, within(1e-6));
    }

    @Test
    void simulateTrial_withdrawalTaxPaidFromLossLots_lossOffsetsAndNeverCreatesNegativeTax() {
        // Same year funded from lots with a 100,000 embedded LOSS: the realized -4,000 floors LTCG
        // income at 0 -> no LTCG tax and, crucially, no negative tax (final is exactly the pre-D5 figure).
        double finalBalance = finalBalanceOfTaxFundedFromTaxable(300_000.0);

        assertThat(finalBalance).isEqualTo(652_000.0, within(1e-6));
    }
}
