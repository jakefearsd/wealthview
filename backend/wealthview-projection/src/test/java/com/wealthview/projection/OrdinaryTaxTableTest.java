package com.wealthview.projection;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.mfj2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2022Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Audit C5: {@link OrdinaryTaxTable} replaces the old $50k-chord {@code MarginalRateCalculator}
 * with an allocation-free, primitive-array table that reproduces
 * {@link FederalTaxCalculator#computeTax} EXACTLY at any income point, not just its average slope
 * over a fixed $50k probe.
 *
 * <p>Test 1 (design doc): table construction is oracle-checked against {@code computeTax} at
 * bracket floors/mid/ceilings for 3 years x both filing statuses.
 * Test 2: an incremental draw crossing 2 brackets prices as the true piecewise sum, and the
 * chord would over/under-price it -- both are asserted so the fix is demonstrably not a no-op.
 */
class OrdinaryTaxTableTest {

    private static FederalTaxCalculator singleCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2022, "single"))
                .thenReturn(single2022Brackets());
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    private static FederalTaxCalculator mfjCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubMfj2025(taxBracketRepo, deductionRepo);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    private static FederalTaxCalculator age65Calc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    // === Test 1: table construction matches the computeTax oracle at floors/mid/ceilings ===

    @Test
    void taxAt_single2025_matchesComputeTaxOracleAtBracketFloorsAndMidpointsAndCeilings() {
        var calc = singleCalc();
        var table = OrdinaryTaxTable.build(calc, 2025, FilingStatus.SINGLE, -1);

        double[] probes = {0, 10_000, 11_925, 30_000, 48_475, 75_000, 103_350, 150_000,
                197_300, 224_000, 250_525, 400_000, 626_350, 900_000};
        for (double gross : probes) {
            double expected = calc.computeTax(bd(String.valueOf(gross)), 2025, FilingStatus.SINGLE).doubleValue();
            assertThat(table.taxAt(gross))
                    .as("gross=%s", gross)
                    .isCloseTo(expected, within(1e-6));
        }
    }

    @Test
    void taxAt_mfj2025_matchesComputeTaxOracle() {
        var calc = mfjCalc();
        var table = OrdinaryTaxTable.build(calc, 2025, FilingStatus.MARRIED_FILING_JOINTLY, -1);

        double[] probes = {0, 23_850, 60_000, 96_950, 300_000, 501_050, 751_600, 1_000_000};
        for (double gross : probes) {
            double expected = calc.computeTax(bd(String.valueOf(gross)), 2025, FilingStatus.MARRIED_FILING_JOINTLY)
                    .doubleValue();
            assertThat(table.taxAt(gross))
                    .as("gross=%s", gross)
                    .isCloseTo(expected, within(1e-6));
        }
    }

    @Test
    void taxAt_single2022_matchesComputeTaxOracle_thirdSeededYear() {
        var calc = singleCalc();
        var table = OrdinaryTaxTable.build(calc, 2022, FilingStatus.SINGLE, -1);

        double[] probes = {0, 10_275, 41_775, 89_075, 170_050, 215_950, 539_900, 700_000};
        for (double gross : probes) {
            double expected = calc.computeTax(bd(String.valueOf(gross)), 2022, FilingStatus.SINGLE).doubleValue();
            assertThat(table.taxAt(gross))
                    .as("gross=%s", gross)
                    .isCloseTo(expected, within(1e-6));
        }
    }

    @Test
    void build_ageAware_matchesComputeTaxOracleWithBoostedDeduction() {
        var calc = age65Calc();
        var table = OrdinaryTaxTable.build(calc, 2025, FilingStatus.SINGLE, 66);

        double expected = calc.computeTax(bd("60000"), 2025, FilingStatus.SINGLE, 66).doubleValue();
        assertThat(table.taxAt(60_000)).isCloseTo(expected, within(1e-6));
        assertThat(expected).isCloseTo(4831.50, within(1e-6));
    }

    @Test
    void computeAll_noCalculator_returnsZeroTablesNotNullArray() {
        var tables = OrdinaryTaxTable.computeAll(null, 2025, 3, FilingStatus.SINGLE, null);

        assertThat(tables).hasSize(3);
        for (var table : tables) {
            assertThat(table).isNotNull();
            assertThat(table.taxAt(500_000)).isZero();
            assertThat(table.incrementalTax(100_000, 50_000)).isZero();
            assertThat(table.rateAt(500_000)).isZero();
        }
    }

    // === Test 2: exact incremental pricing vs the old $50k chord ===

    @Test
    void incrementalTax_drawCrossingTwoBrackets_pricesTruePiecewiseSum_notTheChordAverage() {
        var calc = singleCalc();
        var table = OrdinaryTaxTable.build(calc, 2025, FilingStatus.SINGLE, -1);

        // Base income 40,000, netted by the $15,000 single-2025 standard deduction -> taxable
        // 25,000 (already inside the 12% bracket: 11,925-48,475). A $60,000 draw stacks taxable
        // income to 85,000, crossing the 12%->22% boundary at taxable 48,475 (bracket rate touches
        // BOTH the 12% and 22% brackets). True piecewise tax on the draw:
        //   12% slice: 48,475 - 25,000 = 23,475 @ 12% = 2,817.00
        //   22% slice: 85,000 - 48,475 = 36,525 @ 22% = 8,035.50
        //   true incremental = 10,852.50
        double exact = table.incrementalTax(40_000, 60_000);
        assertThat(exact).isCloseTo(10_852.50, within(1e-6));

        // The OLD $50k-probe chord rate at this base (computeTax(90,000)-computeTax(40,000))/50,000
        // is a DIFFERENT number from the true incremental tax on THIS draw, proving the fix is not
        // a no-op: pricing the $60,000 draw with that flat chord rate misprices it.
        double chordBase = calc.computeTax(bd("40000"), 2025, FilingStatus.SINGLE).doubleValue();
        double chordProbe = calc.computeTax(bd("90000"), 2025, FilingStatus.SINGLE).doubleValue();
        double chordRate = (chordProbe - chordBase) / 50_000.0;
        double chordPriced = 60_000 * chordRate;

        assertThat(chordPriced).isNotCloseTo(exact, within(1.0));
        // Exact must equal the two-endpoint difference over the true bracket walk (oracle).
        double oracleExact = calc.computeTax(bd("100000"), 2025, FilingStatus.SINGLE).doubleValue()
                - calc.computeTax(bd("40000"), 2025, FilingStatus.SINGLE).doubleValue();
        assertThat(exact).isCloseTo(oracleExact, within(1e-6));
    }

    @Test
    void incrementalTax_zeroOrNegativeDraw_returnsZero() {
        var calc = singleCalc();
        var table = OrdinaryTaxTable.build(calc, 2025, FilingStatus.SINGLE, -1);

        assertThat(table.incrementalTax(50_000, 0)).isZero();
        assertThat(table.incrementalTax(50_000, -100)).isZero();
    }

    // === rateAt: used for the (deliberately-approximate) gross-up "m" and forceRmdExcess ===

    @Test
    void rateAt_pointInsideABracket_returnsThatBracketsRate() {
        var calc = singleCalc();
        var table = OrdinaryTaxTable.build(calc, 2025, FilingStatus.SINGLE, -1);

        // Single 2025: $15,000 deduction. gross=30,000 -> taxable=15,000 (12% bracket);
        // gross=90,000 -> taxable=75,000 (22% bracket); gross=700,000 -> taxable=685,000 (37%, top).
        assertThat(table.rateAt(30_000)).isCloseTo(0.12, within(1e-9));
        assertThat(table.rateAt(90_000)).isCloseTo(0.22, within(1e-9));
        assertThat(table.rateAt(700_000)).isCloseTo(0.37, within(1e-9));
    }

    @Test
    void rateAt_belowDeduction_returnsZero() {
        var calc = singleCalc();
        var table = OrdinaryTaxTable.build(calc, 2025, FilingStatus.SINGLE, -1);

        assertThat(table.rateAt(5_000)).isZero();
    }
}
