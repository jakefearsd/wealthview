package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Audit C5, test 3 (design doc): the OLD {@code LtcgRateCalculator} probed the LTCG bracket at a
 * floor that omitted the trial's actual traditional draws/conversions -- a year where those draws
 * push total ordinary income across the $48,350 single-2025 0%/15% LTCG boundary was silently
 * priced at 0%. {@link LtcgTaxTable} fixes this by evaluating at the ACTUAL stacking floor (base +
 * conversion + traditional draw), known at trial-evaluation time.
 */
class LtcgTaxTableTest {

    private static CapitalGainsTaxCalculator capitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    private static FederalTaxCalculator federalTaxCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    @Test
    void taxAt_lowFloor_gainFitsInZeroBracket_taxIsZero() {
        var table = LtcgTaxTable.build(capitalGainsCalc(), federalTaxCalc(), 2025, FilingStatus.SINGLE, 0, 0.0, -1);

        // Base ordinary 20,000 - 15,000 deduction = 5,000 net; +1,000 gain = 6,000, well inside 0%.
        assertThat(table.taxAt(20_000, 1_000)).isCloseTo(0.0, within(1e-9));
    }

    // === Test 3: the previously-omitted stacking floor fix ===

    @Test
    void taxAt_omittedTraditionalDrawPushesFloorAcrossBoundary_pricesAt15PercentNotZero() {
        var table = LtcgTaxTable.build(capitalGainsCalc(), federalTaxCalc(), 2025, FilingStatus.SINGLE, 0, 0.0, -1);

        // Year's income-source base is only 30,000 (well under the deduction-netted 0% ceiling:
        // 30,000-15,000=15,000). A $40,000 traditional withdrawal THIS TRIAL (previously omitted by
        // LtcgRateCalculator, which only ever saw the static income-source base) pushes the actual
        // stacking floor to 70,000 gross -> netted 55,000, ABOVE the $48,350 boundary.
        // A $5,000 realized gain: 0% for the first (48,350-55,000 already exceeds it, so ALL of it
        // is in the 15% bracket) -> 5,000 * 0.15 = 750.00 (MAGI 70,000+5,000=75,000, well under the
        // $200k NIIT threshold, so no NIIT).
        double floorFromBaseAlone = 30_000; // what the OLD code would have stacked on (bug)
        double floorWithActualDraw = 30_000 + 40_000; // NEW: base + this trial's actual draw

        double preFixBuggyTax = table.taxAt(floorFromBaseAlone, 5_000);
        double fixedTax = table.taxAt(floorWithActualDraw, 5_000);

        assertThat(preFixBuggyTax).as("pre-fix: floor from base alone stays under $48,350 -> 0%")
                .isCloseTo(0.0, within(1e-9));
        assertThat(fixedTax).as("fixed: floor includes the actual draw, crossing into 15%")
                .isCloseTo(750.0, within(1e-6));
    }

    @Test
    void taxAt_niitThresholdDeflatesOverHorizon_matchesCapitalGainsTaxCalculatorOracle() {
        var table0 = LtcgTaxTable.build(capitalGainsCalc(), federalTaxCalc(), 2025, FilingStatus.SINGLE, 0, 0.025, -1);
        var table20 =
                LtcgTaxTable.build(capitalGainsCalc(), federalTaxCalc(), 2025, FilingStatus.SINGLE, 20, 0.025, -1);

        // High floor so the whole gain lands in the 15% bracket regardless of NIIT; magi 220,000.
        double floor = 250_000;
        double gain = 50_000;
        var oracle = capitalGainsCalc();
        double expectedY0 = oracle.computeLtcgTax(bd("250000"), bd("50000"), 2025, FilingStatus.SINGLE, 0,
                bd("0.025"), bd("220000")).doubleValue();
        double expectedY20 = oracle.computeLtcgTax(bd("250000"), bd("50000"), 2025, FilingStatus.SINGLE, 20,
                bd("0.025"), bd("220000")).doubleValue();

        // Reconstruct the same MAGI convention the table uses: magi = grossFloor + gain -- here we
        // pass a floor whose (floor + gain) matches the oracle's magi input of 220,000.
        double tableFloor = 220_000 - gain;
        assertThat(table0.taxAt(tableFloor, gain)).isCloseTo(expectedY0, within(1e-6));
        assertThat(table20.taxAt(tableFloor, gain)).isCloseTo(expectedY20, within(1e-6));
        assertThat(expectedY20).isGreaterThan(expectedY0);
    }

    @Test
    void taxAt_gainPushesIntoTopBracket_matchesOracle() {
        var table = LtcgTaxTable.build(capitalGainsCalc(), federalTaxCalc(), 2025, FilingStatus.SINGLE, 0, 0.0, -1);
        var oracle = capitalGainsCalc();

        // gross floor 520,000 + 15,000 deduction netted back out inside the table -- pass a floor
        // whose NETTED value (floor - deduction) equals the oracle's ordinaryTaxableIncome input.
        double grossFloor = 520_000 + 15_000;
        double expected = oracle.computeLtcgTax(bd("520000"), bd("50000"), 2025, FilingStatus.SINGLE, 0,
                bd("0.025"), bd("0")).doubleValue();

        // MAGI must independently be 0 per the oracle call; the table always computes
        // magi = grossFloor + gain, so this case is exercised via taxAt directly against a
        // fresh oracle call using the table's own magi convention instead.
        double tableResult = table.taxAt(grossFloor, 50_000);
        double oracleWithTableMagi = oracle.computeLtcgTax(bd("520000"), bd("50000"), 2025, FilingStatus.SINGLE, 0,
                bd("0.025"), bd(String.valueOf(grossFloor + 50_000))).doubleValue();

        assertThat(tableResult).isCloseTo(oracleWithTableMagi, within(1e-6));
        assertThat(expected).isCloseTo(9330.0, within(1e-6));
    }

    @Test
    void taxAt_zeroOrNegativeGain_returnsZero() {
        var table = LtcgTaxTable.build(capitalGainsCalc(), federalTaxCalc(), 2025, FilingStatus.SINGLE, 0, 0.0, -1);

        assertThat(table.taxAt(100_000, 0)).isZero();
        assertThat(table.taxAt(100_000, -1_000)).isZero();
    }

    @Test
    void computeAll_noCapitalGainsCalculator_returnsZeroTablesNotNullArray() {
        var tables = LtcgTaxTable.computeAll(null, federalTaxCalc(), 2025, 3, FilingStatus.SINGLE, 0.0, null);

        assertThat(tables).hasSize(3);
        for (var table : tables) {
            assertThat(table).isNotNull();
            assertThat(table.taxAt(1_000_000, 500_000)).isZero();
        }
    }

    @Test
    void build_noFederalTaxCalculator_fallsBackToGrossStackingFloor() {
        // Mirrors LtcgRateCalculator's old ZERO-deduction fallback when no federal calculator is
        // wired: ordinary 50k + 1k probe = 51k > $48,350 -> 15%.
        var table = LtcgTaxTable.build(capitalGainsCalc(), null, 2025, FilingStatus.SINGLE, 0, 0.0, -1);

        double tax = table.taxAt(50_000, 1_000);
        assertThat(tax).isCloseTo(150.0, within(1e-6));
    }
}
