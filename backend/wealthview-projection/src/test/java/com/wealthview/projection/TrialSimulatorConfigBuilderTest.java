package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrialSimulatorConfigBuilderTest {

    @Test
    void build_defaultsOnly_matchesCanonicalNoOpAnchors() {
        // Every optional knob must default to its documented byte-identical no-op
        // value: null tax/conversion/rental arrays, no cash reserve, no tracking,
        // no RMDs (MAX_VALUE), zero dividend/interest yield, 100% taxable equity
        // share, no household, no survivor regimes, survivor factor 1.0.
        double[] taxable = {0.10};
        double[] traditional = {0.02};
        double[] roth = {0.08};

        var built = TrialSimulator.SimulationConfig.builder(100.0, 50.0, 25.0, "taxable_first")
                .returns(taxable, traditional, roth)
                .retirementAge(62)
                .build();

        var canonical = new TrialSimulator.SimulationConfig(
                100.0, 50.0, 25.0, "taxable_first",
                null, null, null, null, 62, null, 0, 0.0, false,
                taxable, traditional, roth, Integer.MAX_VALUE,
                0.0, null, 0.0, null, null, 0.0, 1.0, null, null, 1.0);

        assertThat(built).isEqualTo(canonical);
    }

    @Test
    void build_allKnobsSet_matchesCanonicalConstructor() {
        double[] taxable = {0.10};
        double[] traditional = {0.02};
        double[] roth = {0.08};
        var taxTables = new OrdinaryTaxTable[]{null};
        double[] baseIncome = {1000.0};
        double[] conversions = {5000.0};
        double[] conversionTax = {1200.0};
        double[] dsCeilings = {90000.0};
        var ltcgTables = new LtcgTaxTable[]{null};
        double[] rental = {3000.0};
        var regimes = new TrialSimulator.SurvivorRegime[2];

        var built = TrialSimulator.SimulationConfig.builder(100.0, 50.0, 25.0, "traditional_first")
                .taxTables(taxTables, baseIncome)
                .conversions(conversions, conversionTax)
                .retirementAge(65)
                .rmdStartAge(73)
                .dsBracketCeilingByYear(dsCeilings)
                .cashReserve(2, 0.03)
                .trackYearBalances(true)
                .returns(taxable, traditional, roth)
                .taxableBasis(80.0)
                .ltcgTaxTableByYear(ltcgTables)
                .dividendYield(0.015)
                .adaptation(null)
                .rentalIncomeByYear(rental)
                .interestYield(0.04)
                .taxableEquityShare(0.7)
                .household(null)
                .survivorRegimes(regimes, 0.75)
                .build();

        var canonical = new TrialSimulator.SimulationConfig(
                100.0, 50.0, 25.0, "traditional_first",
                taxTables, baseIncome, conversions, conversionTax, 65, dsCeilings,
                2, 0.03, true, taxable, traditional, roth, 73,
                80.0, ltcgTables, 0.015, null, rental, 0.04, 0.7, null, regimes, 0.75);

        assertThat(built).isEqualTo(canonical);
    }
}
