package com.wealthview.core.projection.tax;

import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.mfj2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.mfjDeduction2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2022Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.singleDeduction2022;
import static com.wealthview.core.testutil.TaxBracketFixtures.singleDeduction2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FederalTaxCalculatorTest {

    @Mock
    private TaxBracketRepository taxBracketRepository;

    @Mock
    private StandardDeductionRepository standardDeductionRepository;

    private FederalTaxCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
    }

    private void stubSingleDeduction2025() {
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(singleDeduction2025()));
    }

    private void stubMfjDeduction2025() {
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(2025, "married_filing_jointly"))
                .thenReturn(Optional.of(mfjDeduction2025()));
    }

    private void stubSingleDeduction2022() {
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(2022, "single"))
                .thenReturn(Optional.of(singleDeduction2022()));
    }

    // === Parameterized single filer 2025 income sweep ===

    @ParameterizedTest(name = "single 2025: income={0} -> tax={1}")
    @CsvSource({
            "-5000,    0.0000",
            "0,        0.0000",
            "10000,    0.0000",
            "15000,    0.0000",
            "15001,    0.1000",
            "50000,    3961.5000",
            "700000,   210470.2500"
    })
    void computeTax_singleFiler2025_variousIncomes(String income, String expectedTax) {
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        stubSingleDeduction2025();

        var tax = calculator.computeTax(bd(income), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(bd(expectedTax));
    }

    @Test
    void computeMaxIncomeForBracket_includesStandardDeduction() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        stubSingleDeduction2025();

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.1200"), 2025, FilingStatus.SINGLE);

        // 12% bracket ceiling $48,475 + standard deduction $15,000 = $63,475
        assertThat(ceiling).isEqualByComparingTo(bd("63475"));
    }

    @Test
    void computeTax_marriedFilingJointly_usesLargerDeduction() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "married_filing_jointly"))
                .thenReturn(mfj2025Brackets());
        stubMfjDeduction2025();

        var tax = calculator.computeTax(bd("150000"), 2025, FilingStatus.MARRIED_FILING_JOINTLY);

        // Gross $150K - deduction $30K = taxable $120K
        // 10% on 0-23850 = 2385.00
        // 12% on 23850-96950 = 8772.00
        // 22% on 96950-120000 = 5071.00
        // Total = 16228.00
        assertThat(tax).isEqualByComparingTo(bd("16228.0000"));
    }

    @Test
    void computeTax_2022Brackets_differentFrom2025() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2022, "single"))
                .thenReturn(single2022Brackets());
        stubSingleDeduction2022();
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        stubSingleDeduction2025();

        var tax2022 = calculator.computeTax(bd("50000"), 2022, FilingStatus.SINGLE);
        calculator.clearCache();
        var tax2025 = calculator.computeTax(bd("50000"), 2025, FilingStatus.SINGLE);

        // 2022: $50K - $12,950 = $37,050. 2025: $50K - $15,000 = $35,000. Different.
        assertThat(tax2022).isNotEqualByComparingTo(tax2025);
    }

    @Test
    void computeTax_futureYear_fallsBackToLatest() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2040, "single"))
                .thenReturn(List.of());
        when(taxBracketRepository.findMaxTaxYear()).thenReturn(2025);
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(standardDeductionRepository.findByTaxYearAndFilingStatus(2040, "single"))
                .thenReturn(Optional.empty());
        when(standardDeductionRepository.findMaxTaxYear()).thenReturn(2025);
        stubSingleDeduction2025();

        var tax = calculator.computeTax(bd("50000"), 2040, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(bd("3961.5000"));
    }

    @Test
    void computeMaxIncomeForBracket_targetRate12_returnsTopOf12BracketPlusDeduction() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        stubSingleDeduction2025();

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.1200"), 2025, FilingStatus.SINGLE);

        // $48,475 + $15,000 = $63,475
        assertThat(ceiling).isEqualByComparingTo(bd("63475"));
    }

    @Test
    void computeMaxIncomeForBracket_targetRate22_returnsTopOf22BracketPlusDeduction() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        stubSingleDeduction2025();

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.2200"), 2025, FilingStatus.SINGLE);

        // $103,350 + $15,000 = $118,350
        assertThat(ceiling).isEqualByComparingTo(bd("118350"));
    }

    @Test
    void computeMaxIncomeForBracket_unknownRate_returnsZero() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.1500"), 2025, FilingStatus.SINGLE);

        assertThat(ceiling).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeMaxIncomeForBracket_highestBracket_returnsZero() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.3700"), 2025, FilingStatus.SINGLE);

        // Top bracket has no ceiling -> returns zero (regardless of deduction)
        assertThat(ceiling).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTax_noDeductionData_treatsAsZeroDeduction() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(standardDeductionRepository.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.empty());
        when(standardDeductionRepository.findMaxTaxYear()).thenReturn(null);

        var tax = calculator.computeTax(bd("50000"), 2025, FilingStatus.SINGLE);

        // No deduction data: tax on full $50K
        // 10% on 0-11925 = 1192.50
        // 12% on 11925-48475 = 4386.00
        // 22% on 48475-50000 = 335.50
        // Total = 5914.00
        assertThat(tax).isEqualByComparingTo(bd("5914.0000"));
    }


    @Test
    void computeTax_deductionFallsBackToLatestYear() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2040, "single"))
                .thenReturn(List.of());
        when(taxBracketRepository.findMaxTaxYear()).thenReturn(2025);
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(standardDeductionRepository.findByTaxYearAndFilingStatus(2040, "single"))
                .thenReturn(Optional.empty());
        when(standardDeductionRepository.findMaxTaxYear()).thenReturn(2025);
        stubSingleDeduction2025();

        var tax2040 = calculator.computeTax(bd("50000"), 2040, FilingStatus.SINGLE);

        // Falls back to 2025 deduction ($15K) and 2025 brackets
        assertThat(tax2040).isEqualByComparingTo(bd("3961.5000"));
    }

    @Test
    void computeMaxIncomeForBracket_noDeduction_returnsBracketCeilingOnly() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(standardDeductionRepository.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.empty());
        when(standardDeductionRepository.findMaxTaxYear()).thenReturn(null);

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.1200"), 2025, FilingStatus.SINGLE);

        // No deduction: just bracket ceiling $48,475
        assertThat(ceiling).isEqualByComparingTo(bd("48475"));
    }

    @Test
    void computeMaxIncomeForBracket_mfj_includesLargerDeduction() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "married_filing_jointly"))
                .thenReturn(mfj2025Brackets());
        stubMfjDeduction2025();

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.1200"), 2025, FilingStatus.MARRIED_FILING_JOINTLY);

        // MFJ 12% bracket ceiling $96,950 + $30,000 deduction = $126,950
        assertThat(ceiling).isEqualByComparingTo(bd("126950"));
    }

    @Test
    void computeTax_cachedDeduction_sameResult() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        stubSingleDeduction2025();

        var tax1 = calculator.computeTax(bd("50000"), 2025, FilingStatus.SINGLE);
        var tax2 = calculator.computeTax(bd("50000"), 2025, FilingStatus.SINGLE);

        assertThat(tax1).isEqualByComparingTo(tax2);
    }

    @Test
    void computeTax_afterClearCache_stillComputes() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        stubSingleDeduction2025();

        calculator.computeTax(bd("50000"), 2025, FilingStatus.SINGLE);
        calculator.clearCache();
        var tax = calculator.computeTax(bd("50000"), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(bd("3961.5000"));
    }
}
