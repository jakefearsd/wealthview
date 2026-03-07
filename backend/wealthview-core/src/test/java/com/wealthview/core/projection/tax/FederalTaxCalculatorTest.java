package com.wealthview.core.projection.tax;

import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FederalTaxCalculatorTest {

    @Mock
    private TaxBracketRepository taxBracketRepository;

    private FederalTaxCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FederalTaxCalculator(taxBracketRepository);
    }

    private List<TaxBracketEntity> single2025Brackets() {
        return List.of(
                new TaxBracketEntity(2025, "single", bd("0"), bd("11925"), bd("0.1000")),
                new TaxBracketEntity(2025, "single", bd("11925"), bd("48475"), bd("0.1200")),
                new TaxBracketEntity(2025, "single", bd("48475"), bd("103350"), bd("0.2200")),
                new TaxBracketEntity(2025, "single", bd("103350"), bd("197300"), bd("0.2400")),
                new TaxBracketEntity(2025, "single", bd("197300"), bd("250525"), bd("0.3200")),
                new TaxBracketEntity(2025, "single", bd("250525"), bd("626350"), bd("0.3500")),
                new TaxBracketEntity(2025, "single", bd("626350"), null, bd("0.3700")));
    }

    private List<TaxBracketEntity> mfj2025Brackets() {
        return List.of(
                new TaxBracketEntity(2025, "married_filing_jointly", bd("0"), bd("23850"), bd("0.1000")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("23850"), bd("96950"), bd("0.1200")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("96950"), bd("206700"), bd("0.2200")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("206700"), bd("394600"), bd("0.2400")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("394600"), bd("501050"), bd("0.3200")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("501050"), bd("751600"), bd("0.3500")),
                new TaxBracketEntity(2025, "married_filing_jointly", bd("751600"), null, bd("0.3700")));
    }

    private List<TaxBracketEntity> single2022Brackets() {
        return List.of(
                new TaxBracketEntity(2022, "single", bd("0"), bd("10275"), bd("0.1000")),
                new TaxBracketEntity(2022, "single", bd("10275"), bd("41775"), bd("0.1200")),
                new TaxBracketEntity(2022, "single", bd("41775"), bd("89075"), bd("0.2200")),
                new TaxBracketEntity(2022, "single", bd("89075"), bd("170050"), bd("0.2400")),
                new TaxBracketEntity(2022, "single", bd("170050"), bd("215950"), bd("0.3200")),
                new TaxBracketEntity(2022, "single", bd("215950"), bd("539900"), bd("0.3500")),
                new TaxBracketEntity(2022, "single", bd("539900"), null, bd("0.3700")));
    }

    @Test
    void computeTax_singleFiler2025_50k_correctBrackets() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());

        var tax = calculator.computeTax(bd("50000"), 2025, FilingStatus.SINGLE);

        // 10% on 0-11925 = 1192.50
        // 12% on 11925-48475 = 4386.00
        // 22% on 48475-50000 = 335.50
        // Total = 5914.00
        assertThat(tax).isEqualByComparingTo(bd("5914.0000"));
    }

    @Test
    void computeTax_marriedFiling2025_150k_correctBrackets() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "married_filing_jointly"))
                .thenReturn(mfj2025Brackets());

        var tax = calculator.computeTax(bd("150000"), 2025, FilingStatus.MARRIED_FILING_JOINTLY);

        // 10% on 0-23850 = 2385.00
        // 12% on 23850-96950 = 8772.00
        // 22% on 96950-150000 = 11671.00
        // Total = 22828.00
        assertThat(tax).isEqualByComparingTo(bd("22828.0000"));
    }

    @Test
    void computeTax_zeroIncome_returnsZero() {
        var tax = calculator.computeTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTax_topBracket_correctAmount() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());

        var tax = calculator.computeTax(bd("700000"), 2025, FilingStatus.SINGLE);

        // 10% on 0-11925 = 1192.50
        // 12% on 11925-48475 = 4386.00
        // 22% on 48475-103350 = 12072.50
        // 24% on 103350-197300 = 22548.00
        // 32% on 197300-250525 = 17032.00
        // 35% on 250525-626350 = 131538.75
        // 37% on 626350-700000 = 27250.50
        // Total = 216020.25
        assertThat(tax).isEqualByComparingTo(bd("216020.2500"));
    }

    @Test
    void computeTax_2022Brackets_differentFrom2025() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2022, "single"))
                .thenReturn(single2022Brackets());
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());

        var tax2022 = calculator.computeTax(bd("50000"), 2022, FilingStatus.SINGLE);
        var tax2025 = calculator.computeTax(bd("50000"), 2025, FilingStatus.SINGLE);

        // 2022 brackets are narrower, so tax should be slightly higher for same income
        assertThat(tax2022).isNotEqualByComparingTo(tax2025);
    }

    @Test
    void computeTax_futureYear_fallsBackToLatest() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2040, "single"))
                .thenReturn(List.of());
        when(taxBracketRepository.findMaxTaxYear()).thenReturn(2025);
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());

        var tax = calculator.computeTax(bd("50000"), 2040, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(bd("5914.0000"));
    }

    @Test
    void computeMaxIncomeForBracket_targetRate12_returnsTopOf12Bracket() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.1200"), 2025, FilingStatus.SINGLE);

        assertThat(ceiling).isEqualByComparingTo(bd("48475"));
    }

    @Test
    void computeMaxIncomeForBracket_targetRate22_returnsTopOf22Bracket() {
        when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());

        var ceiling = calculator.computeMaxIncomeForBracket(bd("0.2200"), 2025, FilingStatus.SINGLE);

        assertThat(ceiling).isEqualByComparingTo(bd("103350"));
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

        assertThat(ceiling).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
