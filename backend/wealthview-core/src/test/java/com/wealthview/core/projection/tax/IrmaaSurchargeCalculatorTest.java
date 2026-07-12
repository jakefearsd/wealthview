package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.persistence.repository.IrmaaTierRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Irmaa;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link IrmaaSurchargeCalculator}'s tier lookup and annual-dollar math against the seeded
 * 2025 figures (see R__seed_irmaa_tiers.sql for sources): standard Part B $185.00/mo, five
 * surcharge tiers with combined Part B+D monthly add-ons of $87.70 / $220.30 / $352.90 / $485.50 /
 * $529.70, annualized (x12).
 */
@ExtendWith(MockitoExtension.class)
class IrmaaSurchargeCalculatorTest {

    @Mock
    private IrmaaTierRepository irmaaTierRepository;

    private IrmaaSurchargeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new IrmaaSurchargeCalculator(irmaaTierRepository);
        stubSingle2025Irmaa(irmaaTierRepository);
        stubMfj2025Irmaa(irmaaTierRepository);
    }

    @Test
    void computeAnnualSurcharge_belowFirstThreshold_returnsZero() {
        // 2025 single first threshold is $106,000 -- at or below it, no surcharge.
        assertThat(calculator.computeAnnualSurcharge(bd("106000"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("0");
    }

    @Test
    void computeAnnualSurcharge_zeroMagi_returnsZero() {
        assertThat(calculator.computeAnnualSurcharge(BigDecimal.ZERO, 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("0");
    }

    @Test
    void computeAnnualSurcharge_nullMagi_returnsZero() {
        assertThat(calculator.computeAnnualSurcharge(null, 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("0");
    }

    @Test
    void computeAnnualSurcharge_justAboveFirstThreshold_secondTier_returnsAnnualizedTierTwo() {
        // $106,001 single: tier 2 ($74.00 Part B + $13.70 Part D = $87.70/mo) x 12 = $1,052.40.
        assertThat(calculator.computeAnnualSurcharge(bd("106001"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("1052.4000");
    }

    @Test
    void computeAnnualSurcharge_atTierCeiling_staysInLowerTier() {
        // $133,000 exactly is the CEILING of tier 2 (inclusive) -- boundary semantics are
        // (floor, ceiling], so $133,000 is still tier 2, not tier 3.
        assertThat(calculator.computeAnnualSurcharge(bd("133000"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("1052.4000");
    }

    @Test
    void computeAnnualSurcharge_justAboveTierCeiling_movesToNextTier() {
        // $133,001: tier 3 ($185.00 + $35.30 = $220.30/mo) x 12 = $2,643.60.
        assertThat(calculator.computeAnnualSurcharge(bd("133001"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("2643.6000");
    }

    @Test
    void computeAnnualSurcharge_topTierUncappedCeiling_returnsTopTierAmount() {
        // $10,000,000 single: top tier ($443.90 + $85.80 = $529.70/mo) x 12 = $6,356.40.
        assertThat(calculator.computeAnnualSurcharge(bd("10000000"), 2025, FilingStatus.SINGLE))
                .isEqualByComparingTo("6356.4000");
    }

    @Test
    void computeAnnualSurcharge_marriedFilingJointly_usesWiderThresholds() {
        // $212,000 MFJ is exactly the first threshold (still tier 1, no surcharge); $250,000 falls
        // in tier 2 ($212,000-$266,000) -- same dollar surcharge as single's tier 2.
        assertThat(calculator.computeAnnualSurcharge(bd("212000"), 2025, FilingStatus.MARRIED_FILING_JOINTLY))
                .isEqualByComparingTo("0");
        assertThat(calculator.computeAnnualSurcharge(bd("250000"), 2025, FilingStatus.MARRIED_FILING_JOINTLY))
                .isEqualByComparingTo("1052.4000");
    }

    @Test
    void computeAnnualSurcharge_unseededYear_fallsBackToLatestSeededYear() {
        var latest = calculator.computeAnnualSurcharge(bd("150000"), 2055, FilingStatus.SINGLE);
        var seeded = calculator.computeAnnualSurcharge(bd("150000"), 2025, FilingStatus.SINGLE);

        assertThat(latest).isEqualByComparingTo(seeded);
    }
}
