package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.projection.dto.TaxSpaceYear;
import com.wealthview.core.projection.dto.YearTaxPicture;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.IrmaaTierRepository;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.mfj2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Hand-worked fixtures against the ACTUAL 2025 seed values (R__seed_*.sql):
 * <ul>
 *   <li>Standard deduction: single 15,750 (+2,000 at 65+); MFJ 31,500 (+1,600 per spouse 65+).</li>
 *   <li>Ordinary single: 10% to 11,925 / 12% to 48,475 / 22% to 103,350 / 24% to 197,300 / 32% to 250,525 /
 *       35% to 626,350 / 37%. MFJ 12% tops out at 96,950.</li>
 *   <li>LTCG single: 0% to 48,350 / 15% to 533,400 / 20%. NIIT threshold 200,000 single / 250,000 MFJ.</li>
 *   <li>SS thresholds single 25,000 / 34,000. IRMAA single tier ceilings 106,000 / 133,000 / 167,000 / ...;
 *       annual per-person surcharges 0 / 1,052.40 / 2,643.60 / 4,234.80 / ...</li>
 * </ul>
 * Year 2025, yearsFromBase 0 (no deflation).
 */
@ExtendWith(MockitoExtension.class)
class TaxSpaceCalculatorTest {

    private static final int YEAR = 2025;
    private static final BigDecimal INFLATION = new BigDecimal("0.025");

    @Mock private TaxBracketRepository taxBracketRepository;
    @Mock private StandardDeductionRepository standardDeductionRepository;
    @Mock private LtcgBracketRepository ltcgBracketRepository;
    @Mock private IrmaaTierRepository irmaaTierRepository;

    private FederalTaxCalculator federal;
    private TaxSpaceCalculator calculator;

    @BeforeEach
    void setUp() {
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(),
                        eq("married_filing_jointly")))
                .thenReturn(mfj2025Brackets());
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(YEAR, "single", bd("15750"), bd("2000"))));
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(anyInt(), eq("married_filing_jointly")))
                .thenReturn(Optional.of(
                        new StandardDeductionEntity(YEAR, "married_filing_jointly", bd("31500"), bd("1600"))));
        stubSingle2025Ltcg(ltcgBracketRepository);
        stubMfj2025Ltcg(ltcgBracketRepository);
        stubSingle2025Irmaa(irmaaTierRepository);
        stubMfj2025Irmaa(irmaaTierRepository);
        federal = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        calculator = new TaxSpaceCalculator(federal, new CapitalGainsTaxCalculator(ltcgBracketRepository),
                new IrmaaSurchargeCalculator(irmaaTierRepository));
    }

    /** A single filer born {@code YEAR - age}, federal-only. */
    private FederalOnlyTaxStrategy singleStrategy(int age) {
        return new FederalOnlyTaxStrategy(federal, YEAR - age);
    }

    private static YearTaxPicture picture(FilingStatus status, int age, Integer spouseAge, String ordinaryExSs,
                                          String ssBenefit, String traditional, String ltcg, String magi,
                                          int medicareCount) {
        return new YearTaxPicture(YEAR, status, age, spouseAge, bd(ordinaryExSs), bd(ssBenefit), BigDecimal.ZERO,
                bd(traditional), BigDecimal.ZERO, BigDecimal.ZERO, bd(ltcg), bd(magi), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, INFLATION, medicareCount);
    }

    @Test
    void compute_zeroIncomeYear_fullDeductionRoomZeroMarginalNoSsNoIrmaa() {
        var p = picture(FilingStatus.SINGLE, 60, null, "0", "0", "0", "0", "0", 0);

        TaxSpaceYear t = calculator.compute(p, singleStrategy(60));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0");
        // Every bracket with a ceiling is listed: ceiling + 15,750 deduction, all room.
        assertThat(t.bracketRoom()).hasSize(6);
        assertThat(t.bracketRoom().get(0).rate()).isEqualByComparingTo("0.10");
        assertThat(t.bracketRoom().get(0).grossCeiling()).isEqualByComparingTo("27675"); // 11925 + 15750
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("27675");
        assertThat(t.bracketRoom().get(1).room()).isEqualByComparingTo("64225");          // 48475 + 15750
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("48350");
        assertThat(t.ltcgFifteenRoom()).isEqualByComparingTo("485050");                   // 533400 - 48350
        assertThat(t.provisionalIncome()).isNull();
        assertThat(t.ssBaseThreshold()).isNull();
        assertThat(t.ssUpperThreshold()).isNull();
        assertThat(t.ssInclusionRate()).isNull();
        assertThat(t.niitHeadroom()).isEqualByComparingTo("200000");
        assertThat(t.irmaaPremiumYear()).isNull();
        assertThat(t.irmaaTier()).isNull();
        assertThat(t.irmaaRoomToNextTier()).isNull();
        assertThat(t.irmaaNextTierAnnualCost()).isNull();
        // +1000 ordinary stays under the deduction; +1000 LTCG sits in the 0% band.
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0");
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0");
        assertThat(t.age()).isEqualTo(60);
        assertThat(t.year()).isEqualTo(YEAR);
    }

    @Test
    void compute_pensionInTwelvePercentBracket_reportsRoomAndTwelvePercentMarginal() {
        // gross 50,000 ; taxable 50,000 - 15,750 = 34,250 -> 12% bracket.
        var p = picture(FilingStatus.SINGLE, 60, null, "50000", "0", "0", "0", "50000", 0);

        var t = calculator.compute(p, singleStrategy(60));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.12");
        assertThat(t.bracketRoom().get(0).rate()).isEqualByComparingTo("0.12");
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("14225");   // 64225 - 50000
        assertThat(t.bracketRoom().get(1).rate()).isEqualByComparingTo("0.22");
        assertThat(t.bracketRoom().get(1).room()).isEqualByComparingTo("69100");   // 103350 + 15750 - 50000
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("14100");                // 48350 - 34250
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.12");    // +1000 x 12%
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0");          // 34250..35250 is in the 0% band
        assertThat(t.niitHeadroom()).isEqualByComparingTo("150000");
    }

    @Test
    void compute_ordinaryPushesGainsFromZeroToFifteen_effectiveOrdinaryIs27Percent() {
        // ordinary 60,000 -> taxable 44,250 (12%) ; LTCG 10,000 stacked 44,250..54,250:
        //   4,100 at 0% (to 48,350) + 5,900 at 15% = 885.
        // +1,000 ordinary: +120 ordinary tax ; 0% room shrinks to 3,100 -> 6,900 at 15% = 1,035 (+150). Total +270.
        // +1,000 LTCG: lands wholly above 48,350 -> +150.
        var p = picture(FilingStatus.SINGLE, 60, null, "60000", "0", "0", "10000", "70000", 0);

        var t = calculator.compute(p, singleStrategy(60));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.12");
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("0");
        assertThat(t.ltcgFifteenRoom()).isEqualByComparingTo("479150");          // 533400 - 54250
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("4225");   // 64225 - 60000
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.27");
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0.15");
    }

    @Test
    void compute_socialSecurityTorpedo_age66_effectiveOrdinaryIs18Point5Percent() {
        // Age 66 -> deduction 15,750 + 2,000 = 17,750. SS 30,000, pension 20,000.
        // provisional = 20,000 + 15,000 = 35,000 > 34,000:
        //   taxable SS = min(15,000, 4,500) + (1,000 x 0.85) = 5,350 (cap 25,500).
        // gross ordinary 25,350 ; taxable 7,600 -> 10% -> 760.
        // +1,000 ordinary: SS taxable 4,500 + 2,000 x 0.85 = 6,200 (+850) ; gross 27,200 ; taxable 9,450 -> 945 (+185).
        // +1,000 LTCG: provisional +1,000 -> SS +850 -> taxable 8,450 -> 845 (+85) ; the gain itself is in 0%.
        var p = picture(FilingStatus.SINGLE, 66, null, "20000", "30000", "0", "0", "25350", 1);

        var t = calculator.compute(p, singleStrategy(66));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.10");
        assertThat(t.provisionalIncome()).isEqualByComparingTo("35000");
        assertThat(t.ssBaseThreshold()).isEqualByComparingTo("25000");
        assertThat(t.ssUpperThreshold()).isEqualByComparingTo("34000");
        assertThat(t.ssInclusionRate()).isEqualByComparingTo("0.85");
        assertThat(t.bracketRoom().get(0).rate()).isEqualByComparingTo("0.10");
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("4325");   // 11925 + 17750 - 25350
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("40750");               // 48350 - 7600
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.185");
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0.085");
        // IRMAA: medicare-age in 2027, MAGI 25,350 sits in tier 0 (<= 106,000).
        assertThat(t.irmaaPremiumYear()).isEqualTo(2027);
        assertThat(t.irmaaTier()).isZero();
        assertThat(t.irmaaRoomToNextTier()).isEqualByComparingTo("80650");        // 106000 - 25350
        assertThat(t.irmaaNextTierAnnualCost()).isEqualByComparingTo("1052.40");  // (74.00 + 13.70) x 12 x 1
    }

    @ParameterizedTest(name = "pension {0} -> provisional {1}, inclusion {2}")
    @CsvSource({
            // SS 20,000 (half = 10,000), single, age 66, no deflation.
            "10000, 20000, 0.00",   // below 25,000 base
            "15000, 25000, 0.50",   // EXACTLY at the base: the next dollar is taxed at 50%
            "20000, 30000, 0.50",   // inside tier 1 (taxable 2,500 < 50% cap)
            "24000, 34000, 0.85",   // EXACTLY at the upper threshold: next dollar enters tier 2
            "40000, 50000, 0.00"    // tier 2 but already capped at 85% of benefits (17,000)
    })
    void compute_ssInclusionRate_acrossThresholdBoundaries(String pension, String provisional, String inclusion) {
        var p = picture(FilingStatus.SINGLE, 66, null, pension, "20000", "0", "0", pension, 1);

        var t = calculator.compute(p, singleStrategy(66));

        assertThat(t.provisionalIncome()).isEqualByComparingTo(provisional);
        assertThat(t.ssInclusionRate()).isEqualByComparingTo(inclusion);
    }

    @Test
    void compute_mfjBothOver65_twoAddersAndIrmaaCostForTwoEnrollees() {
        // Household: primary 1958 (67), spouse 1959 (66), both alive. Deduction 31,500 + 2 x 1,600 = 34,700.
        // Traditional 120,000 -> taxable 85,300 -> 12% (MFJ 12% to 96,950).
        var household = HouseholdContext.of(1958, 95, 1959, 95, 2060);
        var strategy = new FederalOnlyTaxStrategy(federal, 1958, household);
        var p = picture(FilingStatus.MARRIED_FILING_JOINTLY, 67, 66, "0", "0", "120000", "0", "120000", 2);

        var t = calculator.compute(p, strategy);

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.12");
        assertThat(t.bracketRoom().get(0).grossCeiling()).isEqualByComparingTo("131650"); // 96950 + 34700
        assertThat(t.bracketRoom().get(0).room()).isEqualByComparingTo("11650");
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.12");
        assertThat(t.niitHeadroom()).isEqualByComparingTo("130000");                     // 250000 - 120000
        assertThat(t.irmaaTier()).isZero();
        assertThat(t.irmaaRoomToNextTier()).isEqualByComparingTo("92000");               // 212000 - 120000
        assertThat(t.irmaaNextTierAnnualCost()).isEqualByComparingTo("2104.80");         // 1052.40 x 2
    }

    @Test
    void compute_irmaaMidTier_reportsTierIndexRoomAndIncrementalCost() {
        // MAGI 140,000 single: tiers <=106k (0), <=133k (1), <=167k (2) -> tier 2.
        // Next-tier cost: (295.90 + 57.00 - 185.00 - 35.30) x 12 = 132.60 x 12 = 1,591.20.
        var p = picture(FilingStatus.SINGLE, 70, null, "0", "0", "140000", "0", "140000", 1);

        var t = calculator.compute(p, singleStrategy(70));

        assertThat(t.irmaaTier()).isEqualTo(2);
        assertThat(t.irmaaRoomToNextTier()).isEqualByComparingTo("27000");               // 167000 - 140000
        assertThat(t.irmaaNextTierAnnualCost()).isEqualByComparingTo("1591.20");
    }

    @Test
    void compute_irmaaTopTier_reportsTierIndexWithNoRoomOrNextCost() {
        // MAGI 600,000 single is above the 500,000 ceiling of tier 4 -> top tier 5, no tier beyond it.
        var p = picture(FilingStatus.SINGLE, 70, null, "0", "0", "600000", "0", "600000", 1);

        var t = calculator.compute(p, singleStrategy(70));

        assertThat(t.irmaaTier()).isEqualTo(5);
        assertThat(t.irmaaRoomToNextTier()).isNull();
        assertThat(t.irmaaNextTierAnnualCost()).isNull();
    }

    @Test
    void compute_withoutIrmaaCalculator_irmaaFieldsAreNull() {
        var noIrmaa = new TaxSpaceCalculator(federal, new CapitalGainsTaxCalculator(ltcgBracketRepository), null);
        var p = picture(FilingStatus.SINGLE, 70, null, "0", "0", "140000", "0", "140000", 1);

        var t = noIrmaa.compute(p, singleStrategy(70));

        assertThat(t.irmaaPremiumYear()).isNull();
        assertThat(t.irmaaTier()).isNull();
    }

    @Test
    void compute_overNiitThreshold_negativeHeadroomAndNiitInBothMarginals() {
        // Single, age 60: ordinary 180,000 (taxable 164,250, 24%) + LTCG 50,000 -> MAGI 230,000.
        // NIIT base = min(50,000, 230,000 - 200,000) = 30,000.
        // +1,000 ordinary: 24% = 240 ; NIIT base -> 31,000 (+38). Total 278.
        // +1,000 LTCG: 15% = 150 (164,250..215,250 is all in the 15% band) ; NIIT +38. Total 188.
        var p = picture(FilingStatus.SINGLE, 60, null, "180000", "0", "0", "50000", "230000", 0);

        var t = calculator.compute(p, singleStrategy(60));

        assertThat(t.marginalOrdinaryRate()).isEqualByComparingTo("0.24");
        assertThat(t.niitHeadroom()).isEqualByComparingTo("-30000");
        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("0");
        assertThat(t.ltcgFifteenRoom()).isEqualByComparingTo("319150");                  // 533400 - 214250
        assertThat(t.effectiveMarginalOrdinary()).isEqualByComparingTo("0.278");
        assertThat(t.effectiveMarginalLtcg()).isEqualByComparingTo("0.188");
    }

    @Test
    void compute_strategyDeductionTakesPrecedenceOverPictureAges() {
        // The strategy knows the filer is 66 (deduction 17,750); the picture claims 60. Strategy wins:
        // gross 25,350 -> taxable 7,600 -> 0% LTCG room 48,350 - 7,600 = 40,750 (a picture-age lookup
        // would give 15,750 -> taxable 9,600 -> 38,750).
        var p = picture(FilingStatus.SINGLE, 60, null, "25350", "0", "0", "0", "25350", 0);

        var t = calculator.compute(p, singleStrategy(66));

        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("40750");
    }

    @Test
    void compute_strategyWithoutDeductionConcept_fallsBackToPictureAges() {
        // A bare strategy: Optional.empty() standard deduction -> picture age 66 -> 17,750.
        TaxCalculationStrategy bare = new TaxCalculationStrategy() {
            @Override
            public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
                return federal.computeTax(grossIncome, taxYear, status, 66);
            }

            @Override
            public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear,
                                                             FilingStatus status) {
                return federal.computeMaxIncomeForBracket(targetRate, taxYear, status);
            }
        };
        var p = picture(FilingStatus.SINGLE, 66, null, "25350", "0", "0", "0", "25350", 0);

        var t = calculator.compute(p, bare);

        assertThat(t.ltcgZeroRoom()).isEqualByComparingTo("40750");
    }
}
