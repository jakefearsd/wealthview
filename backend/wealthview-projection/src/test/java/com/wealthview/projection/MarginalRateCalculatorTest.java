package com.wealthview.projection;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Verifies {@link MarginalRateCalculator} probes {@link FederalTaxCalculator} with a fixed income
 * increment, and (audit D) applies the IRS age-65+ additional standard deduction when a birth year
 * is supplied and the primary filer turns 65+ in that projected tax year -- keeping the MC
 * marginal-rate precompute consistent with the deterministic engine's age-aware deduction.
 */
class MarginalRateCalculatorTest {

    /** Single-filer 2025 brackets, with a deduction carrying a nonzero age-65 addition. */
    private static FederalTaxCalculator federalTaxCalcWithAge65Addition() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    @Test
    void compute_noCalculator_returnsAllZeros() {
        double[] rates = MarginalRateCalculator.compute(
                null, new double[]{100_000, 100_000}, 2025, 2, FilingStatus.SINGLE);

        assertThat(rates).containsExactly(0.0, 0.0);
    }

    @Test
    void compute_noBirthYear_ageUnaware() {
        var calc = federalTaxCalcWithAge65Addition();

        // deduction 15,750 (age-unaware); base 0 -> baseTax 0; probe 50,000 -> taxable 34,250,
        // straddling the 11,925/12% boundary: 1,192.50 + (34,250-11,925)*0.12 = 3,871.50.
        double[] rates = MarginalRateCalculator.compute(calc, new double[]{0}, 2025, 1, FilingStatus.SINGLE);

        assertThat(rates[0]).isEqualTo(3871.5 / 50_000, within(1e-9));
    }

    @Test
    void compute_birthYearMakesFilerAge65InYearZero_boostsDeductionLowersRate() {
        var calc = federalTaxCalcWithAge65Addition();
        int retirementYear = 2025;
        int birthYear = 1959; // age 66 at 2025

        double[] rateAgeAware = MarginalRateCalculator.compute(
                calc, new double[]{0}, retirementYear, 1, FilingStatus.SINGLE, birthYear);
        double[] rateAgeless = MarginalRateCalculator.compute(
                calc, new double[]{0}, retirementYear, 1, FilingStatus.SINGLE);

        // Age-aware: deduction 17,750; taxable 32,250 -> 1,192.50 + 20,325*0.12 = 3,631.50.
        assertThat(rateAgeAware[0]).isEqualTo(3631.5 / 50_000, within(1e-9));
        // Direction: a bigger deduction shifts the probe window further left, strictly lowering the
        // blended marginal rate here (both endpoints straddle the same 12% bracket boundary).
        assertThat(rateAgeAware[0]).isLessThan(rateAgeless[0]);
    }

    @Test
    void compute_birthYearAge64_behavesAgeUnaware() {
        var calc = federalTaxCalcWithAge65Addition();
        int retirementYear = 2025;
        int birthYear = 1961; // age 64 at 2025 -- one below the threshold

        double[] rateAge64 = MarginalRateCalculator.compute(
                calc, new double[]{0}, retirementYear, 1, FilingStatus.SINGLE, birthYear);
        double[] rateAgeless = MarginalRateCalculator.compute(
                calc, new double[]{0}, retirementYear, 1, FilingStatus.SINGLE);

        assertThat(rateAge64[0]).isEqualTo(rateAgeless[0], within(1e-9));
    }
}
