package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.singleDeduction2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.lenient;

/**
 * Bracket inflation-indexing — the fallback that runs for essentially EVERY projected year.
 *
 * <p>Tax brackets are seeded only through 2025 (R__seed_tax_brackets.sql), so any projection
 * reaching past that year finds no brackets for the requested year, falls back to the latest
 * seeded year, and inflates both the bracket ceiling and the standard deduction by
 * {@code (1 + rate)^(taxYear - maxYear)} — mirroring the IRS practice of indexing thresholds to
 * CPI. A projection running 30 years out takes this path 30 times; getting it wrong silently
 * shifts every future-year bracket boundary and therefore every Roth-conversion ceiling.
 *
 * <p>{@link FederalTaxCalculatorTest} covers the un-indexed 3-arg overload; this class covers the
 * 4-arg {@code bracketInflationRate} overload and the guards that decide when indexing applies.
 *
 * <p>Every case uses the single-2025 brackets (12% bracket ceiling $48,475) and a $15,000
 * deduction, with a 10% rate over a 2-year gap so the compounding factor is exactly
 * {@code 1.1^2 = 1.21}:
 * <ul>
 *   <li>indexed → 48,475 x 1.21 = 58,654.75, plus 15,000 x 1.21 = 18,150 → <b>76,804.75</b></li>
 *   <li>not indexed → 48,475 + 15,000 → <b>63,475</b></li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FederalTaxCalculatorBracketIndexingTest {

    private static final int SEEDED_YEAR = 2025;
    private static final int UNSEEDED_FUTURE_YEAR = 2027;
    private static final BigDecimal RATE_12_PERCENT = bd("0.12");
    private static final BigDecimal TEN_PERCENT = bd("0.10");

    /** 48,475 x 1.21 + 15,000 x 1.21 */
    private static final BigDecimal INDEXED_CEILING_PLUS_DEDUCTION = bd("76804.75");
    /** 48,475 + 15,000 */
    private static final BigDecimal RAW_CEILING_PLUS_DEDUCTION = bd("63475");

    @Mock
    private TaxBracketRepository taxBracketRepository;

    @Mock
    private StandardDeductionRepository standardDeductionRepository;

    private FederalTaxCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
    }

    /** Only 2025 is seeded, in both repositories; every other year must fall back to it. */
    private void seedOnly2025() {
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                SEEDED_YEAR, "single")).thenReturn(single2025Brackets());
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                intThat(y -> y != SEEDED_YEAR),
                eq("single"))).thenReturn(List.of());
        lenient().when(taxBracketRepository.findMaxTaxYear()).thenReturn(SEEDED_YEAR);

        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(SEEDED_YEAR, "single"))
                .thenReturn(Optional.of(singleDeduction2025()));
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(
                intThat(y -> y != SEEDED_YEAR),
                eq("single"))).thenReturn(Optional.empty());
        lenient().when(standardDeductionRepository.findMaxTaxYear()).thenReturn(SEEDED_YEAR);
    }

    // === the indexing itself ===

    @Test
    void computeMaxIncomeForBracket_unseededFutureYear_compoundsRateOverTheYearGap() {
        seedOnly2025();

        var ceiling = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, UNSEEDED_FUTURE_YEAR, FilingStatus.SINGLE, TEN_PERCENT);

        assertThat(ceiling)
                .as("2027 is two years past the seeded 2025, so both the bracket ceiling and the "
                        + "deduction inflate by 1.1^2 = 1.21")
                .isEqualByComparingTo(INDEXED_CEILING_PLUS_DEDUCTION);
    }

    @Test
    void computeMaxIncomeForBracket_unseededFutureYear_inflatesTheDeductionNotJustTheCeiling() {
        seedOnly2025();

        var indexed = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, UNSEEDED_FUTURE_YEAR, FilingStatus.SINGLE, TEN_PERCENT);

        // Guards against the plausible half-fix of indexing the bracket but leaving the deduction
        // nominal, which would land at 58,654.75 + 15,000 = 73,654.75 instead.
        assertThat(indexed).isNotEqualByComparingTo(bd("73654.75"));
        assertThat(indexed.subtract(bd("58654.75")))
                .as("the deduction component must itself be inflated to 18,150")
                .isEqualByComparingTo(bd("18150"));
    }

    @Test
    void computeMaxIncomeForBracket_longerGap_compoundsRatherThanScalesLinearly() {
        seedOnly2025();

        // 2035 is a 10-year gap, so the factor is 1.1^10 = 2.5937424601 (exact in BigDecimal —
        // pow() does not round). The ceiling and the deduction are inflated and rounded to 4dp
        // INDEPENDENTLY, then added, which is why the total cannot be reproduced by inflating
        // their sum in one step:
        //   ceiling   48,475 x 2.5937424601 = 125,731.6657533475 -> 125,731.6658
        //   deduction 15,000 x 2.5937424601 =  38,906.1369015    ->  38,906.1369
        //   total                                                   164,637.8027
        // Rounding the combined 63,475 first would land on ...8026 — a real, if tiny, difference.
        var ceiling = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, 2035, FilingStatus.SINGLE, TEN_PERCENT);

        assertThat(ceiling).isEqualByComparingTo(bd("164637.8027"));
        assertThat(ceiling)
                .as("compounding, not linear scaling: a linear (1 + 10 x 0.1) = 2.0 factor would "
                        + "give only 126,950")
                .isGreaterThan(bd("126950"));
    }

    // === the guards that switch indexing off ===

    @Test
    void computeMaxIncomeForBracket_nullInflationRate_fallsBackWithoutIndexing() {
        seedOnly2025();

        var ceiling = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, UNSEEDED_FUTURE_YEAR, FilingStatus.SINGLE, null);

        assertThat(ceiling).isEqualByComparingTo(RAW_CEILING_PLUS_DEDUCTION);
    }

    @Test
    void computeMaxIncomeForBracket_zeroInflationRate_fallsBackWithoutIndexing() {
        seedOnly2025();

        var ceiling = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, UNSEEDED_FUTURE_YEAR, FilingStatus.SINGLE, BigDecimal.ZERO);

        assertThat(ceiling).isEqualByComparingTo(RAW_CEILING_PLUS_DEDUCTION);
    }

    @Test
    void computeMaxIncomeForBracket_negativeInflationRate_doesNotDeflateThresholds() {
        seedOnly2025();

        var ceiling = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, UNSEEDED_FUTURE_YEAR, FilingStatus.SINGLE, bd("-0.10"));

        assertThat(ceiling)
                .as("a negative rate is rejected outright rather than shrinking the brackets")
                .isEqualByComparingTo(RAW_CEILING_PLUS_DEDUCTION);
    }

    @Test
    void computeMaxIncomeForBracket_unseededPastYear_doesNotIndexBackwards() {
        seedOnly2025();

        var ceiling = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, 2020, FilingStatus.SINGLE, TEN_PERCENT);

        assertThat(ceiling)
                .as("taxYear <= maxYear must leave thresholds alone; indexing only ever projects forward")
                .isEqualByComparingTo(RAW_CEILING_PLUS_DEDUCTION);
    }

    @Test
    void computeMaxIncomeForBracket_seededYear_ignoresTheInflationRateEntirely() {
        seedOnly2025();

        var ceiling = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, SEEDED_YEAR, FilingStatus.SINGLE, TEN_PERCENT);

        assertThat(ceiling)
                .as("real seeded brackets are authoritative — no synthetic indexing on top")
                .isEqualByComparingTo(RAW_CEILING_PLUS_DEDUCTION);
    }

    // === degenerate bracket data ===

    @Test
    void computeMaxIncomeForBracket_noBracketDataInAnyYear_returnsZero() {
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                anyInt(), anyString()))
                .thenReturn(List.of());
        lenient().when(taxBracketRepository.findMaxTaxYear()).thenReturn(null);
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(
                anyInt(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(standardDeductionRepository.findMaxTaxYear()).thenReturn(null);

        var ceiling = calculator.computeMaxIncomeForBracket(
                RATE_12_PERCENT, UNSEEDED_FUTURE_YEAR, FilingStatus.SINGLE, TEN_PERCENT);

        assertThat(ceiling).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeMaxIncomeForBracket_topBracketHasNoCeiling_returnsZeroEvenWhenIndexing() {
        seedOnly2025();

        var ceiling = calculator.computeMaxIncomeForBracket(
                bd("0.37"), UNSEEDED_FUTURE_YEAR, FilingStatus.SINGLE, TEN_PERCENT);

        assertThat(ceiling)
                .as("the top bracket is unbounded, so there is no ceiling to index")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // === findBracketCeiling: the deduction-free sibling ===

    @Test
    void findBracketCeiling_seededYear_returnsRawCeilingWithoutDeduction() {
        seedOnly2025();

        assertThat(calculator.findBracketCeiling(RATE_12_PERCENT, SEEDED_YEAR, FilingStatus.SINGLE))
                .isEqualByComparingTo(bd("48475"));
    }

    @Test
    void findBracketCeiling_unseededYear_fallsBackToLatestSeededYear() {
        seedOnly2025();

        assertThat(calculator.findBracketCeiling(RATE_12_PERCENT, UNSEEDED_FUTURE_YEAR, FilingStatus.SINGLE))
                .as("findBracketCeiling never indexes — it is a raw taxable-income ceiling lookup")
                .isEqualByComparingTo(bd("48475"));
    }

    @Test
    void findBracketCeiling_topBracket_returnsZero() {
        seedOnly2025();

        assertThat(calculator.findBracketCeiling(bd("0.37"), SEEDED_YEAR, FilingStatus.SINGLE))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void findBracketCeiling_rateNotPresentInAnyBracket_returnsZero() {
        seedOnly2025();

        assertThat(calculator.findBracketCeiling(bd("0.99"), SEEDED_YEAR, FilingStatus.SINGLE))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // === computeTaxWithDeduction: deduction swallowing the income ===

    @Test
    void computeTaxWithDeduction_deductionExceedsGrossIncome_returnsZero() {
        seedOnly2025();

        var tax = calculator.computeTaxWithDeduction(
                bd("10000"), bd("15000"), SEEDED_YEAR, FilingStatus.SINGLE);

        assertThat(tax)
                .as("a retiree drawing less than the standard deduction owes no federal tax — and "
                        + "the negative taxable income must not wrap into a bracket walk")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTaxWithDeduction_deductionExactlyEqualsGrossIncome_returnsZero() {
        seedOnly2025();

        var tax = calculator.computeTaxWithDeduction(
                bd("15000"), bd("15000"), SEEDED_YEAR, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
