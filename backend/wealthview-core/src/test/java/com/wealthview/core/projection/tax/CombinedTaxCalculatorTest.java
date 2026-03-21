package com.wealthview.core.projection.tax;

import com.wealthview.core.testutil.TaxBracketFixtures;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class CombinedTaxCalculatorTest {

    @Mock
    private TaxBracketRepository taxBracketRepo;

    @Mock
    private StandardDeductionRepository deductionRepo;

    private FederalTaxCalculator federalCalc;

    @BeforeEach
    void setUp() {
        federalCalc = new FederalTaxCalculator(taxBracketRepo, deductionRepo);
        TaxBracketFixtures.stubSingle2025(taxBracketRepo, deductionRepo);
    }

    private CombinedTaxCalculator buildCombined(StateTaxCalculator stateCalc,
                                                 BigDecimal propertyTax,
                                                 BigDecimal mortgageInterest) {
        return new CombinedTaxCalculator(federalCalc, stateCalc, propertyTax, mortgageInterest);
    }

    @Test
    void computeTax_noStateTax_usesStandardDeduction() {
        var combined = buildCombined(new NullStateTaxCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);

        CombinedTaxResult result = combined.computeTax(bd("100000"), 2025, FilingStatus.SINGLE);

        assertThat(result.stateTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.federalTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.totalTax()).isEqualByComparingTo(result.federalTax());
        assertThat(result.usedItemized()).isFalse();
        assertThat(result.saltDeduction()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTax_withStateTax_computesSALTAndItemized() {
        // State tax of $8000, property tax $5000 = SALT $10000 (capped)
        // Mortgage interest $8000
        // Itemized = $10000 + $8000 = $18000
        // Standard = $15000
        // Should use itemized ($18000 > $15000)
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("8000"));
        var combined = buildCombined(mockState, bd("5000"), bd("8000"));

        CombinedTaxResult result = combined.computeTax(bd("100000"), 2025, FilingStatus.SINGLE);

        assertThat(result.stateTax()).isEqualByComparingTo(bd("8000"));
        assertThat(result.saltDeduction()).isEqualByComparingTo(bd("10000")); // capped
        assertThat(result.itemizedDeductions()).isEqualByComparingTo(bd("18000"));
        assertThat(result.usedItemized()).isTrue();
        // Federal tax should use $18000 deduction instead of $15000
        assertThat(result.federalTax()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void computeTax_saltCap_at10000() {
        // State tax $15000, property tax $12000 = SALT uncapped $27000, capped $10000
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("15000"));
        var combined = buildCombined(mockState, bd("12000"), BigDecimal.ZERO);

        CombinedTaxResult result = combined.computeTax(bd("200000"), 2025, FilingStatus.SINGLE);

        assertThat(result.saltDeduction()).isEqualByComparingTo(bd("10000"));
        assertThat(result.itemizedDeductions()).isEqualByComparingTo(bd("10000")); // SALT only, no mortgage
    }

    @Test
    void computeTax_itemizedLessThanStandard_usesStandard() {
        // State tax $2000, property tax $1000 = SALT $3000
        // Mortgage interest $1000
        // Itemized = $3000 + $1000 = $4000
        // Standard = $15000
        // Should use standard
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("2000"));
        var combined = buildCombined(mockState, bd("1000"), bd("1000"));

        CombinedTaxResult result = combined.computeTax(bd("100000"), 2025, FilingStatus.SINGLE);

        assertThat(result.saltDeduction()).isEqualByComparingTo(bd("3000"));
        assertThat(result.itemizedDeductions()).isEqualByComparingTo(bd("4000"));
        assertThat(result.usedItemized()).isFalse();
    }

    @Test
    void computeTotalTax_returnsSumOfFederalAndState() {
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("5000"));
        var combined = buildCombined(mockState, BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal total = combined.computeTotalTax(bd("100000"), 2025, FilingStatus.SINGLE);

        CombinedTaxResult result = combined.computeTax(bd("100000"), 2025, FilingStatus.SINGLE);
        assertThat(total).isEqualByComparingTo(result.totalTax());
    }

    @Test
    void computeTax_zeroIncome_returnsAllZeros() {
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("0"));
        var combined = buildCombined(mockState, bd("5000"), bd("8000"));

        CombinedTaxResult result = combined.computeTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE);

        assertThat(result.federalTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.stateTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeMaxIncomeForCombinedRate_federalOnly_matchesFederalBehavior() {
        var combined = buildCombined(new NullStateTaxCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);

        // With null state, the combined rate target should behave similarly to federal-only
        // For a 22% target rate with standard deduction of $15000
        BigDecimal maxIncome = combined.computeMaxIncomeForTargetRate(bd("0.2200"), 2025, FilingStatus.SINGLE);

        // The 22% bracket ceiling is $103350, plus standard deduction $15000 = $118350
        assertThat(maxIncome).isEqualByComparingTo(bd("118350"));
    }

    @Test
    void computeMaxIncomeForTargetRate_withStateTax_22vs24_returnsDifferentCeilings() {
        TaxBracketFixtures.stubMfj2025(taxBracketRepo, deductionRepo);
        var mfjCalc = new FederalTaxCalculator(taxBracketRepo, deductionRepo);

        // Use a proportional state calculator (~6% effective rate) to simulate a real state
        StateTaxCalculator proportionalState = new ProportionalStateTaxCalculator("CA", bd("0.06"));
        var combined = new CombinedTaxCalculator(mfjCalc, proportionalState, bd("5500"), BigDecimal.ZERO);

        BigDecimal ceiling22 = combined.computeMaxIncomeForTargetRate(bd("0.2200"), 2025, FilingStatus.MARRIED_FILING_JOINTLY);
        BigDecimal ceiling24 = combined.computeMaxIncomeForTargetRate(bd("0.2400"), 2025, FilingStatus.MARRIED_FILING_JOINTLY);

        // 22% bracket ceiling for MFJ = $206,700, 24% = $394,600
        // These are different brackets, so the ceilings MUST differ
        assertThat(ceiling24).isGreaterThan(ceiling22);
        // The difference should be substantial (bracket is ~$188K wide)
        assertThat(ceiling24.subtract(ceiling22)).isGreaterThan(bd("100000"));
    }

    @Test
    void computeMaxIncomeForTargetRate_withStateTax_12vs22_returnsDifferentCeilings() {
        StateTaxCalculator proportionalState = new ProportionalStateTaxCalculator("CA", bd("0.04"));
        var combined = buildCombined(proportionalState, BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal ceiling12 = combined.computeMaxIncomeForTargetRate(bd("0.1200"), 2025, FilingStatus.SINGLE);
        BigDecimal ceiling22 = combined.computeMaxIncomeForTargetRate(bd("0.2200"), 2025, FilingStatus.SINGLE);

        // 12% ceiling = $48,475 + deduction, 22% ceiling = $103,350 + deduction
        assertThat(ceiling22).isGreaterThan(ceiling12);
        assertThat(ceiling22.subtract(ceiling12)).isGreaterThan(bd("40000"));
    }

    @Test
    void computeMaxIncomeForTargetRate_withStateTax_usesCorrectDeduction() {
        // High state tax + property tax → itemized > standard
        // State tax 8%, property tax $12000, mortgage interest $15000
        // At ~$200K income: state tax ~$16K, SALT = min($16K + $12K, $10K) = $10K
        // Itemized = $10K + $15K = $25K > standard $15K → uses itemized
        StateTaxCalculator proportionalState = new ProportionalStateTaxCalculator("CA", bd("0.08"));
        var combined = buildCombined(proportionalState, bd("12000"), bd("15000"));

        BigDecimal ceiling22 = combined.computeMaxIncomeForTargetRate(bd("0.2200"), 2025, FilingStatus.SINGLE);

        // 22% bracket ceiling = $103,350
        // With itemized deduction ($25K), gross income ceiling = $103,350 + $25,000 = $128,350
        // With standard deduction ($15K), gross income ceiling = $103,350 + $15,000 = $118,350
        // The ceiling should be larger than federal-only because itemized > standard
        assertThat(ceiling22).isGreaterThan(bd("118350"));
    }

    @Test
    void computeMaxIncomeForTargetRate_withStateTax_topBracket_returnsZero() {
        StateTaxCalculator proportionalState = new ProportionalStateTaxCalculator("CA", bd("0.05"));
        var combined = buildCombined(proportionalState, BigDecimal.ZERO, BigDecimal.ZERO);

        // 37% is the top bracket (no ceiling)
        BigDecimal ceiling37 = combined.computeMaxIncomeForTargetRate(bd("0.3700"), 2025, FilingStatus.SINGLE);

        assertThat(ceiling37).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Test helper: a StateTaxCalculator that always returns a fixed amount of tax.
     */
    private record FixedStateTaxCalculator(String code, BigDecimal fixedTax) implements StateTaxCalculator {

        @Override
        public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
            return grossIncome.compareTo(BigDecimal.ZERO) > 0 ? fixedTax : BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getStandardDeduction(int taxYear, FilingStatus status) {
            return BigDecimal.ZERO;
        }

        @Override
        public String stateCode() {
            return code;
        }

        @Override
        public boolean taxesCapitalGainsAsOrdinaryIncome() {
            return true;
        }
    }

    /**
     * Test helper: a StateTaxCalculator that returns tax as a flat percentage of gross income.
     * More realistic than FixedStateTaxCalculator for testing marginal rate interactions.
     */
    private record ProportionalStateTaxCalculator(String code, BigDecimal rate) implements StateTaxCalculator {

        @Override
        public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
            return grossIncome.compareTo(BigDecimal.ZERO) > 0
                    ? grossIncome.multiply(rate).setScale(4, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getStandardDeduction(int taxYear, FilingStatus status) {
            return BigDecimal.ZERO;
        }

        @Override
        public String stateCode() {
            return code;
        }

        @Override
        public boolean taxesCapitalGainsAsOrdinaryIncome() {
            return true;
        }
    }
}
