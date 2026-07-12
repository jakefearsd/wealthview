package com.wealthview.core.projection.tax;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.persistence.repository.LtcgBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.mfj2025LtcgBrackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025LtcgBrackets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapitalGainsTaxCalculatorTest {

    @Mock
    private LtcgBracketRepository ltcgBracketRepository;

    private CapitalGainsTaxCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new CapitalGainsTaxCalculator(ltcgBracketRepository);
        lenient().when(ltcgBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025LtcgBrackets());
    }

    @Test
    void computeLtcgTax_lowOrdinaryIncome_gainFitsInZeroBracket_taxIsZero() {
        // ordinary 20000, ltcg 20000, single 2025: 0% ceiling 48350; 20000+20000=40000 < 48350 -> 0 tax, no NIIT.
        assertThat(calculator.computeLtcgTax(bd("20000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("40000"))).isEqualByComparingTo("0");
    }

    @Test
    void computeLtcgTax_gainStraddlesZeroAnd15Ceiling_taxesOnlyThePortionAbove() {
        // ordinary 40000, ltcg 20000: 8350 fits under 48350 at 0%, 11650 at 15% = 1747.50
        assertThat(calculator.computeLtcgTax(bd("40000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("60000"))).isEqualByComparingTo("1747.5000");
    }

    @Test
    void computeLtcgTax_niitThresholdDeflatesOverHorizon_taxesMoreLater() {
        // High MAGI so NIIT applies, but NOT already saturated at y0: gap = 220000 - 200000 = 20000,
        // which is BELOW the 50000 ltcg cap, so NIIT is partial at y0 (760.00). By y20 the fixed-nominal
        // $200k threshold has eroded (deflator ~0.6103 at 2.5% inflation) to ~122060, pushing the gap to
        // ~97940 -> now ABOVE the 50000 cap, so NIIT saturates at its max (1900.00). y20 > y0 either way,
        // since erosion can only shrink the threshold and thus grow (or hold flat, once saturated) the gap.
        //
        // NOTE: the brief's original magi (300000) already put the y0 gap (100000) above the 50000 ltcg
        // cap, so NIIT was already saturated at y0 and threshold erosion could never move it -- y0 and y20
        // would come out EXACTLY equal (9400.0000 both), not "greater than". Lowered magi to 220000 so the
        // test actually exercises the partial -> saturated transition it claims to.
        var y0 = calculator.computeLtcgTax(bd("250000"), bd("50000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("220000"));
        var y20 = calculator.computeLtcgTax(bd("250000"), bd("50000"), 2025, FilingStatus.SINGLE, 20, bd("0.025"),
                bd("220000"));

        assertThat(y20).isGreaterThan(y0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5000"})
    void computeLtcgTax_zeroOrNegativeGain_returnsZero(String ltcgIncome) {
        var tax = calculator.computeLtcgTax(bd("50000"), bd(ltcgIncome), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("300000"));

        assertThat(tax).isEqualByComparingTo("0");
    }

    @Test
    void computeLtcgTax_gainPushesIntoTopBracket_taxedAt20Percent() {
        // ordinary 520000 sits inside the 15% bracket [48350,533400]; only 13400 of headroom remains
        // there before the gain crosses into the top (null-ceiling) 20% bracket.
        // 13400 * 0.15 = 2010.00, remaining 36600 * 0.20 = 7320.00 -> 9330.00. magi 0 -> no NIIT.
        var tax = calculator.computeLtcgTax(bd("520000"), bd("50000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("0"));

        assertThat(tax).isEqualByComparingTo("9330.0000");
    }

    @Test
    void computeLtcgTax_marriedFilingJointly_usesWiderBracketsAndHigherNiitThreshold() {
        when(ltcgBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "married_filing_jointly"))
                .thenReturn(mfj2025LtcgBrackets());

        // ordinary 250000 already exceeds the MFJ 0% ceiling (96700), so all 50000 of gain lands in the
        // 15% bracket: 50000 * 0.15 = 7500.00.
        // NIIT: MFJ threshold 250000 vs magi 260000 -> gap 10000, below the 50000 cap -> 10000 * 0.038 = 380.00.
        var tax = calculator.computeLtcgTax(bd("250000"), bd("50000"), 2025, FilingStatus.MARRIED_FILING_JOINTLY, 0,
                bd("0.025"), bd("260000"));

        assertThat(tax).isEqualByComparingTo("7880.0000");
    }

    @Test
    void computeLtcgTax_futureYear_fallsBackToLatestSeededBrackets() {
        when(ltcgBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2040, "single"))
                .thenReturn(List.of());
        when(ltcgBracketRepository.findMaxTaxYear()).thenReturn(2025);

        var tax = calculator.computeLtcgTax(bd("20000"), bd("20000"), 2040, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("40000"));

        // Falls back to the seeded 2025 brackets: same result as the zero-bracket test above.
        assertThat(tax).isEqualByComparingTo("0");
    }

    @Test
    void computeLtcgTax_noBracketDataAnywhere_bracketPortionIsZero() {
        when(ltcgBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2040, "single"))
                .thenReturn(List.of());
        when(ltcgBracketRepository.findMaxTaxYear()).thenReturn(null);

        var tax = calculator.computeLtcgTax(bd("40000"), bd("20000"), 2040, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("0"));

        assertThat(tax).isEqualByComparingTo("0");
    }

    @Test
    void computeLtcgTax_afterClearCache_stillComputes() {
        calculator.computeLtcgTax(bd("40000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"), bd("60000"));
        calculator.clearCache();

        var tax = calculator.computeLtcgTax(bd("40000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("60000"));

        assertThat(tax).isEqualByComparingTo("1747.5000");
    }

    // === loadLtcgBrackets / niitThresholdReal (audit C5): expose raw bracket + threshold data ===

    @Test
    void loadLtcgBrackets_seededYear_returnsBracketsInFloorAscendingOrderUnchanged() {
        var brackets = calculator.loadLtcgBrackets(2025, FilingStatus.SINGLE);

        assertThat(brackets).hasSize(3);
        assertThat(brackets.get(0).floor()).isEqualByComparingTo(bd("0"));
        assertThat(brackets.get(0).ceiling()).isEqualByComparingTo(bd("48350"));
        assertThat(brackets.get(0).rate()).isEqualByComparingTo(bd("0.0000"));
        assertThat(brackets.get(2).floor()).isEqualByComparingTo(bd("533400"));
        assertThat(brackets.get(2).ceiling()).isNull();
        assertThat(brackets.get(2).rate()).isEqualByComparingTo(bd("0.2000"));
    }

    @Test
    void loadLtcgBrackets_unseededYear_fallsBackToLatestSeededYear() {
        when(ltcgBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2040, "single"))
                .thenReturn(List.of());
        when(ltcgBracketRepository.findMaxTaxYear()).thenReturn(2025);

        var brackets = calculator.loadLtcgBrackets(2040, FilingStatus.SINGLE);

        assertThat(brackets).hasSize(3);
        assertThat(brackets.get(0).ceiling()).isEqualByComparingTo(bd("48350"));
    }

    @Test
    void niitThresholdReal_zeroYearsFromBase_returnsNominalThreshold() {
        assertThat(calculator.niitThresholdReal(FilingStatus.SINGLE, 0, bd("0.025")))
                .isEqualByComparingTo(bd("200000"));
        assertThat(calculator.niitThresholdReal(FilingStatus.MARRIED_FILING_JOINTLY, 0, bd("0.025")))
                .isEqualByComparingTo(bd("250000"));
    }

    // === T18a-3: net rental income joins the NIIT Net Investment Income base ===

    @Test
    void computeLtcgTax_netRentalIncomeOmitted_matchesExplicitZeroOverload() {
        // The 7-arg overload must stay byte-identical to threading an explicit zero -- no observable
        // behavior change for any caller that doesn't opt into the rental parameter.
        var withoutOverload = calculator.computeLtcgTax(bd("250000"), bd("20000"), 2025, FilingStatus.SINGLE, 0,
                bd("0.025"), bd("270000"));
        var withExplicitZero = calculator.computeLtcgTax(bd("250000"), bd("20000"), 2025, FilingStatus.SINGLE, 0,
                bd("0.025"), bd("270000"), bd("0"));

        assertThat(withoutOverload).isEqualByComparingTo(withExplicitZero);
        assertThat(withoutOverload).isEqualByComparingTo("3760.0000"); // 3000 bracket (15%) + 760 NIIT
    }

    @Test
    void computeLtcgTax_netRentalIncomeAddedToNiitBase_increasesNiitButNotBracketTax() {
        // ordinary 250000, ltcg 20000: entirely in the 15% bracket -> bracket tax 3000.00 (matches
        // the zero-rental case above) -- rental income must NOT touch this component.
        // NIIT: magi 270000 - threshold 200000 = 70000 excess headroom. Without rental, the NII pot
        // is just the 20000 ltcg -> niit 760.00 (as above). With 30000 of net rental income folded
        // into NII, the pot grows to 50000 (still under the 70000 excess) -> niit 1900.00.
        var tax = calculator.computeLtcgTax(bd("250000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("270000"), bd("30000"));

        assertThat(tax).isEqualByComparingTo("4900.0000"); // 3000 bracket + 1900 NIIT
    }

    @Test
    void computeLtcgTax_netRentalLoss_reducesNiitBase() {
        // A net rental LOSS aggregates into NII like any other IRC 1411 component, shrinking (not
        // just failing to grow) the NIIT base: NII pot = 20000 ltcg - 15000 rental loss = 5000 ->
        // niit 190.00 (below the zero-rental case's 760.00).
        var tax = calculator.computeLtcgTax(bd("250000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("270000"), bd("-15000"));

        assertThat(tax).isEqualByComparingTo("3190.0000"); // 3000 bracket + 190 NIIT
    }

    @Test
    void computeLtcgTax_netRentalIncomeCapsAtMagiExcess_niitStillBoundedByThreshold() {
        // Even with a large rental figure, NIIT can never exceed 3.8% of the magi-over-threshold
        // excess (70000 here): NII pot = 20000 + 200000 = 220000, but niitBase caps at the 70000
        // excess -> niit = 70000 * 0.038 = 2660.00 (the statutory ceiling, not 220000 * 0.038).
        var tax = calculator.computeLtcgTax(bd("250000"), bd("20000"), 2025, FilingStatus.SINGLE, 0, bd("0.025"),
                bd("270000"), bd("200000"));

        assertThat(tax).isEqualByComparingTo("5660.0000"); // 3000 bracket + 2660 NIIT
    }

    @Test
    void niitThresholdReal_erodesOverHorizon_matchesComputeLtcgTaxDeflation() {
        // Cross-check against computeLtcgTax's OWN internal deflation (same test shape as
        // computeLtcgTax_niitThresholdDeflatesOverHorizon_taxesMoreLater above): at y20/2.5%
        // inflation the $200k threshold erodes to ~122,060 -- reproduce that exact figure via the
        // now-public accessor instead of only inferring it indirectly through a tax delta.
        var y0 = calculator.niitThresholdReal(FilingStatus.SINGLE, 0, bd("0.025"));
        var y20 = calculator.niitThresholdReal(FilingStatus.SINGLE, 20, bd("0.025"));

        assertThat(y0).isEqualByComparingTo(bd("200000"));
        assertThat(y20).isLessThan(y0);
        assertThat(y20).isCloseTo(bd("122060"), org.assertj.core.data.Offset.offset(bd("50")));
    }
}
