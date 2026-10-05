package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.mfj2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FederalOnlyTaxStrategyTest {

    @Mock
    private FederalTaxCalculator federalTaxCalculator;

    private FederalOnlyTaxStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new FederalOnlyTaxStrategy(federalTaxCalculator);
    }

    @Test
    void computeTotalTax_withPositiveIncome_delegatesToFederalTaxCalculator() {
        var grossIncome = new BigDecimal("75000");
        var expectedTax = new BigDecimal("8760.5000");
        when(federalTaxCalculator.computeTax(grossIncome, 2025, FilingStatus.SINGLE))
                .thenReturn(expectedTax);

        var result = strategy.computeTotalTax(grossIncome, 2025, FilingStatus.SINGLE);

        assertThat(result).isEqualByComparingTo(expectedTax);
        verify(federalTaxCalculator).computeTax(grossIncome, 2025, FilingStatus.SINGLE);
    }

    @Test
    void computeTotalTax_withZeroIncome_returnsZero() {
        when(federalTaxCalculator.computeTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE))
                .thenReturn(BigDecimal.ZERO);

        var result = strategy.computeTotalTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeMaxIncomeForTargetRate_delegatesToFederalTaxCalculator() {
        var targetRate = new BigDecimal("0.1200");
        var expectedCeiling = new BigDecimal("63475");
        when(federalTaxCalculator.computeMaxIncomeForBracket(targetRate, 2025, FilingStatus.SINGLE))
                .thenReturn(expectedCeiling);

        var result = strategy.computeMaxIncomeForTargetRate(targetRate, 2025, FilingStatus.SINGLE);

        assertThat(result).isEqualByComparingTo(expectedCeiling);
        verify(federalTaxCalculator).computeMaxIncomeForBracket(targetRate, 2025, FilingStatus.SINGLE);
    }

    @Test
    void computeDetailedTax_withPositiveIncome_returnsResultWithZeroStateTax() {
        var grossIncome = new BigDecimal("100000");
        var federalTax = new BigDecimal("12345.6789");
        when(federalTaxCalculator.computeTax(grossIncome, 2025, FilingStatus.MARRIED_FILING_JOINTLY))
                .thenReturn(federalTax);

        var result = strategy.computeDetailedTax(grossIncome, 2025, FilingStatus.MARRIED_FILING_JOINTLY);

        assertThat(result.federalTax()).isEqualByComparingTo(federalTax);
        assertThat(result.stateTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTax()).isEqualByComparingTo(federalTax);
        assertThat(result.saltDeduction()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.itemizedDeductions()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeDetailedTax_withPositiveIncome_returnsFalseForUsedItemized() {
        var grossIncome = new BigDecimal("150000");
        var federalTax = new BigDecimal("20000.0000");
        when(federalTaxCalculator.computeTax(grossIncome, 2025, FilingStatus.SINGLE))
                .thenReturn(federalTax);

        var result = strategy.computeDetailedTax(grossIncome, 2025, FilingStatus.SINGLE);

        assertThat(result.usedItemized()).isFalse();
    }

    @Test
    void computeDetailedTax_withZeroIncome_returnsZeroFederalTax() {
        when(federalTaxCalculator.computeTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE))
                .thenReturn(BigDecimal.ZERO);

        var result = strategy.computeDetailedTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE);

        assertThat(result.federalTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.stateTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.usedItemized()).isFalse();
    }

    // === Household task 7: household-aware age threading (spec §4 step 6) ===

    @Test
    void computeTotalTax_householdBothAliveMfj_passesBothFilerAges() {
        var household = HouseholdContext.of(1958, 85, 1966, 90, 2065);
        var householdStrategy = new FederalOnlyTaxStrategy(federalTaxCalculator, null, household);
        var grossIncome = new BigDecimal("100000");
        when(federalTaxCalculator.computeTax(grossIncome, 2042, FilingStatus.MARRIED_FILING_JOINTLY, 84, 76))
                .thenReturn(new BigDecimal("9000"));

        var result = householdStrategy.computeTotalTax(grossIncome, 2042, FilingStatus.MARRIED_FILING_JOINTLY);

        assertThat(result).isEqualByComparingTo(bd("9000"));
        verify(federalTaxCalculator)
                .computeTax(grossIncome, 2042, FilingStatus.MARRIED_FILING_JOINTLY, 84, 76);
    }

    @Test
    void computeTotalTax_householdFilingSingleWhileBothAlive_noSecondAgeEvenThoughSpouseExists() {
        var household = HouseholdContext.of(1958, 85, 1966, 90, 2065);
        var householdStrategy = new FederalOnlyTaxStrategy(federalTaxCalculator, null, household);
        var grossIncome = new BigDecimal("100000");
        when(federalTaxCalculator.computeTax(grossIncome, 2042, FilingStatus.SINGLE, 84, null))
                .thenReturn(new BigDecimal("15000"));

        householdStrategy.computeTotalTax(grossIncome, 2042, FilingStatus.SINGLE);

        verify(federalTaxCalculator).computeTax(grossIncome, 2042, FilingStatus.SINGLE, 84, null);
    }

    @Test
    void computeTotalTax_householdPostTransitionSurvivorSpouse_usesSurvivorAgeNotDeceasedPrimaryAge() {
        // Primary (born 1958) dies at 85 in 2043; the survivor (spouse, born 1966) is 79 in 2045.
        var household = HouseholdContext.of(1958, 85, 1966, 90, 2065);
        var householdStrategy = new FederalOnlyTaxStrategy(federalTaxCalculator, null, household);
        var grossIncome = new BigDecimal("60000");
        when(federalTaxCalculator.computeTax(grossIncome, 2045, FilingStatus.SINGLE, 79, null))
                .thenReturn(new BigDecimal("7000"));

        householdStrategy.computeTotalTax(grossIncome, 2045, FilingStatus.SINGLE);

        verify(federalTaxCalculator).computeTax(grossIncome, 2045, FilingStatus.SINGLE, 79, null);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void computeGrossCeilingForRate_birthYearAge66_usesAgeAwareDeduction() {
        var bracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(bracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var realStrategy = new FederalOnlyTaxStrategy(new FederalTaxCalculator(bracketRepo, deductionRepo), 1959);

        // age 66 in 2025: 12% ceiling 48475 + 15750 + 2000 = 66225 (the age-less method still says 64225)
        assertThat(realStrategy.computeGrossCeilingForRate(bd("0.12"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("66225");
        assertThat(realStrategy.computeMaxIncomeForTargetRate(bd("0.12"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("64225");
    }

    @Test
    void computeGrossCeilingForRate_householdBothAliveMfj_addsTwoAdders() {
        var bracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(bracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "married_filing_jointly"))
                .thenReturn(mfj2025Brackets());
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "married_filing_jointly"))
                .thenReturn(Optional.of(
                        new StandardDeductionEntity(2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        var household = HouseholdContext.of(1958, 95, 1959, 95, 2060);
        var realStrategy = new FederalOnlyTaxStrategy(new FederalTaxCalculator(bracketRepo, deductionRepo), 1958,
                household);

        // 96950 + 31500 + 1600 x 2 = 131650
        assertThat(realStrategy.computeGrossCeilingForRate(bd("0.12"), 2025, FilingStatus.MARRIED_FILING_JOINTLY))
                .isEqualByComparingTo("131650");
    }

    @Test
    void standardDeduction_birthYearAge66_returnsAgeAwareAmount() {
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var realStrategy = new FederalOnlyTaxStrategy(
                new FederalTaxCalculator(mock(TaxBracketRepository.class), deductionRepo), 1959);

        // 15,750 + 2,000 (age 66)
        assertThat(realStrategy.standardDeduction(2025, FilingStatus.SINGLE)).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("17750"));
    }

    @Test
    void standardDeduction_householdMfjBothAlive_countsBothAdders() {
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "married_filing_jointly"))
                .thenReturn(Optional.of(
                        new StandardDeductionEntity(2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        var realStrategy = new FederalOnlyTaxStrategy(
                new FederalTaxCalculator(mock(TaxBracketRepository.class), deductionRepo), 1958,
                HouseholdContext.of(1958, 95, 1959, 95, 2060));

        // 31,500 + 1,600 x 2
        assertThat(realStrategy.standardDeduction(2025, FilingStatus.MARRIED_FILING_JOINTLY)).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("34700"));
    }

    @Test
    void standardDeduction_noAgeKnown_returnsBaseAmount() {
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var realStrategy = new FederalOnlyTaxStrategy(
                new FederalTaxCalculator(mock(TaxBracketRepository.class), deductionRepo));

        assertThat(realStrategy.standardDeduction(2025, FilingStatus.SINGLE)).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("15750"));
    }

    @Test
    void computeGrossCeilingForRate_noAgeKnown_matchesAgeLessCeiling() {
        var bracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        when(bracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(2025, "single"))
                .thenReturn(single2025Brackets());
        when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        var realStrategy = new FederalOnlyTaxStrategy(new FederalTaxCalculator(bracketRepo, deductionRepo));

        assertThat(realStrategy.computeGrossCeilingForRate(bd("0.12"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("64225");
    }

    @Test
    void standardDeduction_defaultImplementation_isEmpty() {
        TaxCalculationStrategy bare = new TaxCalculationStrategy() {
            @Override
            public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
                return BigDecimal.ZERO;
            }

            @Override
            public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear,
                                                             FilingStatus status) {
                return BigDecimal.ZERO;
            }
        };

        assertThat(bare.standardDeduction(2025, FilingStatus.SINGLE)).isEmpty();
    }
}
