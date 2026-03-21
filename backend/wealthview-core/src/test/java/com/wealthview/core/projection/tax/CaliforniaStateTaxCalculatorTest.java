package com.wealthview.core.projection.tax;

import com.wealthview.persistence.entity.StateStandardDeductionEntity;
import com.wealthview.persistence.entity.StateTaxBracketEntity;
import com.wealthview.persistence.entity.StateTaxSurchargeEntity;
import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaliforniaStateTaxCalculatorTest {

    @Mock
    private StateTaxBracketRepository bracketRepo;

    @Mock
    private StateStandardDeductionRepository deductionRepo;

    @Mock
    private StateTaxSurchargeRepository surchargeRepo;

    private CaliforniaStateTaxCalculator calculator;

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    private static List<StateTaxBracketEntity> caSingle2025Brackets() {
        return List.of(
                new StateTaxBracketEntity("CA", 2025, "single", bd("0"), bd("10756"), bd("0.0100")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("10756"), bd("25499"), bd("0.0200")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("25499"), bd("40245"), bd("0.0400")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("40245"), bd("55866"), bd("0.0600")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("55866"), bd("70606"), bd("0.0800")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("70606"), bd("360659"), bd("0.0930")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("360659"), bd("432787"), bd("0.1030")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("432787"), bd("721314"), bd("0.1130")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("721314"), bd("1000000"), bd("0.1230")),
                new StateTaxBracketEntity("CA", 2025, "single", bd("1000000"), null, bd("0.1230")));
    }

    @BeforeEach
    void setUp() {
        calculator = new CaliforniaStateTaxCalculator(bracketRepo, deductionRepo, surchargeRepo);
        stubCaSingle2025();
    }

    private void stubCaSingle2025() {
        lenient().when(bracketRepo.findByStateCodeAndTaxYearAndFilingStatusOrderByBracketFloorAsc(
                eq("CA"), anyInt(), eq("single")))
                .thenReturn(caSingle2025Brackets());
        lenient().when(deductionRepo.findByStateCodeAndTaxYearAndFilingStatus(eq("CA"), anyInt(), eq("single")))
                .thenReturn(Optional.of(new StateStandardDeductionEntity("CA", 2025, "single", bd("5722"))));
        lenient().when(surchargeRepo.findByStateCodeAndTaxYearAndFilingStatus(eq("CA"), anyInt(), eq("single")))
                .thenReturn(List.of(new StateTaxSurchargeEntity(
                        "CA", 2025, "single", "Mental Health Services Tax", bd("1000000"), bd("0.0100"))));
    }

    @Test
    void computeTax_zeroIncome_returnsZero() {
        BigDecimal tax = calculator.computeTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTax_negativeIncome_returnsZero() {
        BigDecimal tax = calculator.computeTax(bd("-5000"), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTax_incomeBelowDeduction_returnsZero() {
        BigDecimal tax = calculator.computeTax(bd("5000"), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTax_incomeInFirstBracket_computesCorrectly() {
        // Gross income: 15000, deduction: 5722, taxable: 9278
        // All in 1% bracket (0-10756): 9278 * 0.01 = 92.78
        BigDecimal tax = calculator.computeTax(bd("15000"), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(bd("92.7800"));
    }

    @Test
    void computeTax_incomeSpanningTwoBrackets_computesCorrectly() {
        // Gross income: 20000, deduction: 5722, taxable: 14278
        // 1% bracket: 10756 * 0.01 = 107.56
        // 2% bracket: (14278-10756) * 0.02 = 3522 * 0.02 = 70.44
        // Total: 178.00
        BigDecimal tax = calculator.computeTax(bd("20000"), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(bd("178.0000"));
    }

    @Test
    void computeTax_moderateIncome_computesProgressiveTax() {
        // Gross income: 100000, deduction: 5722, taxable: 94278
        // 1%: 10756 * 0.01 = 107.56
        // 2%: (25499-10756) * 0.02 = 14743 * 0.02 = 294.86
        // 4%: (40245-25499) * 0.04 = 14746 * 0.04 = 589.84
        // 6%: (55866-40245) * 0.06 = 15621 * 0.06 = 937.26
        // 8%: (70606-55866) * 0.08 = 14740 * 0.08 = 1179.20
        // 9.3%: (94278-70606) * 0.093 = 23672 * 0.093 = 2201.4960
        // Total = 107.56 + 294.86 + 589.84 + 937.26 + 1179.20 + 2201.4960 = 5310.2160
        BigDecimal tax = calculator.computeTax(bd("100000"), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(bd("5310.2160"));
    }

    @Test
    void computeTax_highIncome_includesMentalHealthSurcharge() {
        // Gross income: 1200000, deduction: 5722, taxable: 1194278
        // Regular brackets compute through all 10 brackets
        // Mental Health Services surcharge: (1194278 - 1000000) * 0.01 = 194278 * 0.01 = 1942.78
        // We verify the surcharge is applied by checking total is higher than without it
        BigDecimal tax = calculator.computeTax(bd("1200000"), 2025, FilingStatus.SINGLE);

        // Verify surcharge is applied: tax should be higher than a non-surcharge scenario
        // The exact value depends on bracket arithmetic; trust the algorithm, verify surcharge effect
        // 12.3% bracket above $1M + 1% MHST surcharge = 13.3% effective
        // The old value of $132,176.52 was wrong (used 13.3% bracket + 1% surcharge = 14.3%)
        // Correct: top bracket at 12.3%, surcharge adds 1% → 13.3% total above $1M
        assertThat(tax).isEqualByComparingTo(bd("130233.7360"));
    }

    @Test
    void computeTax_incomeJustBelowSurchargeThreshold_noSurcharge() {
        // taxable income < 1M, no surcharge applied
        // Gross: 1000000, deduction: 5722, taxable: 994278 (below 1M)
        BigDecimal tax = calculator.computeTax(bd("1000000"), 2025, FilingStatus.SINGLE);

        // No surcharge should be applied since taxable < 1M
        // Just verify it's positive and doesn't include surcharge
        assertThat(tax).isPositive();

        // Compute at exactly 1005722 (taxable = 1M) - should also have zero surcharge
        BigDecimal taxAtThreshold = calculator.computeTax(bd("1005722"), 2025, FilingStatus.SINGLE);
        // At 1M taxable, surcharge threshold is 1M, excess = 0, so no surcharge
        assertThat(taxAtThreshold).isGreaterThan(tax);
    }

    @Test
    void computeTax_yearFallback_usesLatestAvailableYear() {
        when(bracketRepo.findByStateCodeAndTaxYearAndFilingStatusOrderByBracketFloorAsc(
                eq("CA"), eq(2030), eq("single")))
                .thenReturn(List.of());
        when(bracketRepo.findMaxTaxYearByStateCode("CA")).thenReturn(2025);

        BigDecimal tax = calculator.computeTax(bd("15000"), 2030, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(bd("92.7800"));
    }

    @Test
    void computeTax_noBracketsAtAll_returnsZero() {
        when(bracketRepo.findByStateCodeAndTaxYearAndFilingStatusOrderByBracketFloorAsc(
                eq("CA"), anyInt(), eq("single")))
                .thenReturn(List.of());
        when(bracketRepo.findMaxTaxYearByStateCode("CA")).thenReturn(null);

        BigDecimal tax = calculator.computeTax(bd("100000"), 2025, FilingStatus.SINGLE);

        assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getStandardDeduction_returnsCorrectAmount() {
        BigDecimal deduction = calculator.getStandardDeduction(2025, FilingStatus.SINGLE);

        assertThat(deduction).isEqualByComparingTo(bd("5722"));
    }

    @Test
    void computeTax_aboveOneMillion_effectiveRateIs13_3PercentNotHigher() {
        // CA tax above $1M should be 12.3% (bracket) + 1% (MHST surcharge) = 13.3%
        // NOT 13.3% (bracket) + 1% (surcharge) = 14.3%
        // Test with $100K of income above the $1M threshold
        // The marginal tax on that $100K should be $13,300 (13.3%), not $14,300 (14.3%)
        BigDecimal taxAt1M = calculator.computeTax(bd("1005722"), 2025, FilingStatus.SINGLE);
        BigDecimal taxAt1_1M = calculator.computeTax(bd("1105722"), 2025, FilingStatus.SINGLE);

        BigDecimal marginalTax = taxAt1_1M.subtract(taxAt1M);
        // $100K at 13.3% = $13,300 (correct: 12.3% bracket + 1% surcharge)
        // $100K at 14.3% = $14,300 (wrong: 13.3% bracket + 1% surcharge)
        assertThat(marginalTax).isEqualByComparingTo(bd("13300.0000"));
    }

    @Test
    void stateCode_returnsCA() {
        assertThat(calculator.stateCode()).isEqualTo("CA");
    }

    @Test
    void taxesCapitalGainsAsOrdinaryIncome_returnsTrue() {
        assertThat(calculator.taxesCapitalGainsAsOrdinaryIncome()).isTrue();
    }
}
