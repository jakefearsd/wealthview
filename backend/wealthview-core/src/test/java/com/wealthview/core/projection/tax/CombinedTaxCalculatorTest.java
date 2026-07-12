package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.testutil.TaxBracketFixtures;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.entity.StateStandardDeductionEntity;
import com.wealthview.persistence.entity.StateTaxBracketEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CombinedTaxCalculatorTest {

    @Mock
    private TaxBracketRepository taxBracketRepo;

    @Mock
    private StandardDeductionRepository deductionRepo;

    @Mock
    private StateTaxBracketRepository stateBracketRepo;

    @Mock
    private StateStandardDeductionRepository stateDeductionRepo;

    @Mock
    private StateTaxSurchargeRepository stateSurchargeRepo;

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

    /**
     * A simplified two-bracket CA-like fixture (1% to $10,756, 2% above, $5,722 standard deduction --
     * CA's real single-filer 2025 first bracket/deduction) that taxes capital gains as ordinary
     * income. Enough to hand-verify bracket-crossing arithmetic exactly for the LTCG/SS state-base
     * composition tests below without transcribing CA's full 10-bracket + surcharge table (that
     * fidelity is already pinned in {@link CaliforniaStateTaxCalculatorTest}) -- these tests are about
     * {@link CombinedTaxCalculator}'s state-base composition (audit C3), not CA's exact brackets.
     */
    private BracketBasedStateTaxCalculator caLikeStateCalculator() {
        lenient().when(stateBracketRepo.findByStateCodeAndTaxYearAndFilingStatusOrderByBracketFloorAsc(
                        eq("CA"), anyInt(), eq("single")))
                .thenReturn(List.of(
                        new StateTaxBracketEntity("CA", 2025, "single", bd("0"), bd("10756"), bd("0.0100")),
                        new StateTaxBracketEntity("CA", 2025, "single", bd("10756"), null, bd("0.0200"))));
        lenient().when(stateDeductionRepo.findByStateCodeAndTaxYearAndFilingStatus(eq("CA"), anyInt(), eq("single")))
                .thenReturn(Optional.of(new StateStandardDeductionEntity("CA", 2025, "single", bd("5722"))));
        lenient().when(stateSurchargeRepo.findByStateCodeAndTaxYearAndFilingStatus(eq("CA"), anyInt(), eq("single")))
                .thenReturn(List.of());
        return new BracketBasedStateTaxCalculator(
                "CA", true, stateBracketRepo, stateDeductionRepo, stateSurchargeRepo);
    }

    @Test
    void computeTax_stateTaxesLtcgAsOrdinaryIncome_addsToStateBase() {
        var combined = buildCombined(caLikeStateCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);

        // No LTCG: gross 20000, deduction 5722, taxable 14278 -> 10756@1%=107.56 + 3522@2%=70.44
        var withoutLtcg = combined.computeTax(bd("20000"), 2025, FilingStatus.SINGLE,
                BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(withoutLtcg.stateTax()).isEqualByComparingTo(bd("178.0000"));

        // $15000 of realized LTCG + qualified dividends added to the STATE base as ordinary income
        // (audit C3, CA taxes capital gains as ordinary income): stateBase = 35000, taxable = 29278
        // -> 10756@1%=107.56 + 18522@2%=370.44 = 478.00
        var withLtcg = combined.computeTax(bd("20000"), 2025, FilingStatus.SINGLE,
                bd("15000"), BigDecimal.ZERO);
        assertThat(withLtcg.stateTax()).isEqualByComparingTo(bd("478.0000"));

        // Direction: CA taxable-heavy retirees pay MORE state tax; federal is untouched by LTCG here
        // (LTCG stays federal-only, taxed separately via CapitalGainsTaxCalculator elsewhere).
        assertThat(withLtcg.stateTax()).isGreaterThan(withoutLtcg.stateTax());
        assertThat(withLtcg.federalTax()).isEqualByComparingTo(withoutLtcg.federalTax());
    }

    @Test
    void computeTax_stateDoesNotTaxLtcgAsOrdinaryIncome_stateBaseUnchanged() {
        // Flag off (e.g. a no-income-tax or LTCG-preferential state): LTCG must NOT move the state
        // base at all.
        StateTaxCalculator flagOff = new ConfigurableStateTaxCalculator("ZZ", bd("0.05"), false, true);
        var combined = buildCombined(flagOff, BigDecimal.ZERO, BigDecimal.ZERO);

        var withoutLtcg = combined.computeTax(bd("20000"), 2025, FilingStatus.SINGLE,
                BigDecimal.ZERO, BigDecimal.ZERO);
        var withLtcg = combined.computeTax(bd("20000"), 2025, FilingStatus.SINGLE,
                bd("15000"), BigDecimal.ZERO);

        assertThat(withLtcg.stateTax()).isEqualByComparingTo(withoutLtcg.stateTax());
    }

    @Test
    void computeTax_zeroOrdinaryIncomeWithLtcg_stateStillTaxesLtcg_federalStaysZero() {
        // A retiree drawing ONLY from a taxable brokerage (no traditional withdrawal, conversion, or
        // other ordinary income) still owes CA tax on realized LTCG/dividends (audit C3) even though
        // the ORDINARY (federal) base is zero -- the old all-or-nothing zero-income guard used to
        // return an all-zero result whenever grossIncome<=0, silently dropping this case.
        var combined = buildCombined(caLikeStateCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);

        var result = combined.computeTax(BigDecimal.ZERO, 2025, FilingStatus.SINGLE,
                bd("20000"), BigDecimal.ZERO);

        // stateBase = 20000, taxable = 14278 -> 107.56 + 3522@2%=70.44 = 178.00
        assertThat(result.stateTax()).isEqualByComparingTo(bd("178.0000"));
        assertThat(result.federalTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTax_stateExemptsSocialSecurity_subtractsFromStateBase() {
        var combined = buildCombined(caLikeStateCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);

        // Pre-fix equivalent (3-arg overload): the $12000 federally-taxed SS portion is already
        // folded into the $40000 gross (as it is in production -- SS taxable income flows into
        // effectiveOtherIncome before reaching this seam) and taxed as state income in full.
        // Gross 40000, deduction 5722, taxable 34278 -> 10756@1%=107.56 + 23522@2%=470.44 = 578.00
        var preFix = combined.computeTax(bd("40000"), 2025, FilingStatus.SINGLE);
        assertThat(preFix.stateTax()).isEqualByComparingTo(bd("578.0000"));

        // Post-fix: CA fully exempts Social Security (audit C3) -- subtract the $12000 federally-taxed
        // SS amount from the state base. stateBase = 28000, taxable = 22278 -> 107.56 + 11522@2%=
        // 230.44 = 338.00
        var postFix = combined.computeTax(bd("40000"), 2025, FilingStatus.SINGLE,
                BigDecimal.ZERO, bd("12000"));
        assertThat(postFix.stateTax()).isEqualByComparingTo(bd("338.0000"));

        // Direction: state tax DECREASES; federal is untouched by the SS-taxable figure passed here
        // (it's already baked into the $40000 gross for federal purposes either way).
        assertThat(postFix.stateTax()).isLessThan(preFix.stateTax());
        assertThat(postFix.federalTax()).isEqualByComparingTo(preFix.federalTax());
    }

    @Test
    void computeTax_stateTaxesLtcgAndExemptsSocialSecurity_bothAdjustmentsCompose() {
        var combined = buildCombined(caLikeStateCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);

        // gross 40000 (includes $12000 SS-taxable), + 15000 LTCG, - 12000 SS-exemption
        // stateBase = 43000, taxable = 37278 -> 10756@1%=107.56 + 26522@2%=530.44 = 638.00
        var result = combined.computeTax(bd("40000"), 2025, FilingStatus.SINGLE, bd("15000"), bd("12000"));

        assertThat(result.stateTax()).isEqualByComparingTo(bd("638.0000"));
    }

    /**
     * Test helper: a StateTaxCalculator with configurable proportional rate and C3 flags, for
     * exercising the {@code taxesCapitalGainsAsOrdinaryIncome}/{@code exemptsSocialSecurity} = false
     * paths that the CA-like fixture above (both true) can't reach.
     */
    private record ConfigurableStateTaxCalculator(String code, BigDecimal rate,
                                                   boolean taxesCapitalGainsAsOrdinaryIncome,
                                                   boolean exemptsSocialSecurity)
            implements StateTaxCalculator {

        @Override
        public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
            return grossIncome.compareTo(BigDecimal.ZERO) > 0
                    ? grossIncome.multiply(rate).setScale(4, RoundingMode.HALF_UP)
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
            return taxesCapitalGainsAsOrdinaryIncome;
        }

        @Override
        public boolean exemptsSocialSecurity() {
            return exemptsSocialSecurity;
        }
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
        // Tax year 2031 -- post-OBBBA-sunset, so the pre-OBBBA $10,000 cap applies (audit D;
        // see the 2025-2029-window tests below for the OBBBA $40,000 cap).
        // State tax of $8000, property tax $5000 = SALT $10000 (capped)
        // Mortgage interest $8000
        // Itemized = $10000 + $8000 = $18000
        // Standard = $15000
        // Should use itemized ($18000 > $15000)
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("8000"));
        var combined = buildCombined(mockState, bd("5000"), bd("8000"));

        CombinedTaxResult result = combined.computeTax(bd("100000"), 2031, FilingStatus.SINGLE);

        assertThat(result.stateTax()).isEqualByComparingTo(bd("8000"));
        assertThat(result.saltDeduction()).isEqualByComparingTo(bd("10000")); // capped
        assertThat(result.itemizedDeductions()).isEqualByComparingTo(bd("18000"));
        assertThat(result.usedItemized()).isTrue();
        // Federal tax should use $18000 deduction instead of $15000
        assertThat(result.federalTax()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void computeTax_saltCap_at10000() {
        // Tax year 2031 -- post-OBBBA-sunset $10,000 cap (audit D).
        // State tax $15000, property tax $12000 = SALT uncapped $27000, capped $10000
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("15000"));
        var combined = buildCombined(mockState, bd("12000"), BigDecimal.ZERO);

        CombinedTaxResult result = combined.computeTax(bd("200000"), 2031, FilingStatus.SINGLE);

        assertThat(result.saltDeduction()).isEqualByComparingTo(bd("10000"));
        assertThat(result.itemizedDeductions()).isEqualByComparingTo(bd("10000")); // SALT only, no mortgage
    }

    // === SALT cap year-awareness (audit D: OBBBA $40,000 cap for 2025-2029) ===

    @Test
    void computeTax_saltCap_2027_uses40000ObbbaCap() {
        // Same $27,000 uncapped SALT as computeTax_saltCap_at10000, but at a tax year INSIDE the
        // OBBBA window: $27,000 stays under the $40,000 cap, so it is NOT capped at all here.
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("15000"));
        var combined = buildCombined(mockState, bd("12000"), BigDecimal.ZERO);

        CombinedTaxResult result = combined.computeTax(bd("200000"), 2027, FilingStatus.SINGLE);

        assertThat(result.saltDeduction()).isEqualByComparingTo(bd("27000"));
    }

    @Test
    void computeTax_saltCap_2027_exceeds40000Cap_stillCapped() {
        // Uncapped SALT ($55,000) exceeds even the OBBBA $40,000 cap.
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("45000"));
        var combined = buildCombined(mockState, bd("10000"), BigDecimal.ZERO);

        CombinedTaxResult result = combined.computeTax(bd("300000"), 2027, FilingStatus.SINGLE);

        assertThat(result.saltDeduction()).isEqualByComparingTo(bd("40000"));
    }

    @Test
    void computeTax_saltCap_yearBoundary_2029UsesObbbaCap_2030RevertsToBaseCap() {
        // $27,000 uncapped SALT: under the $40,000 OBBBA cap (2029, last OBBBA year) but over the
        // $10,000 base cap that resumes the very next year (2030, the statutory sunset).
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("15000"));
        var combined = buildCombined(mockState, bd("12000"), BigDecimal.ZERO);

        var result2029 = combined.computeTax(bd("200000"), 2029, FilingStatus.SINGLE);
        var result2030 = combined.computeTax(bd("200000"), 2030, FilingStatus.SINGLE);

        assertThat(result2029.saltDeduction()).isEqualByComparingTo(bd("27000"));
        assertThat(result2030.saltDeduction()).isEqualByComparingTo(bd("10000"));
    }

    @Test
    void computeTax_saltCap_2024_preObbba_uses10000Cap() {
        // A year before OBBBA's 2025 effective date must also use the pre-OBBBA $10,000 cap.
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("15000"));
        var combined = buildCombined(mockState, bd("12000"), BigDecimal.ZERO);

        CombinedTaxResult result = combined.computeTax(bd("200000"), 2024, FilingStatus.SINGLE);

        assertThat(result.saltDeduction()).isEqualByComparingTo(bd("10000"));
    }

    @Test
    void computeTax_saltCap_itemizeDecisionFlips_acrossSunsetBoundary() {
        // State tax $25,000 + property $8,000 = $33,000 uncapped SALT, no mortgage interest.
        // At 2027 (OBBBA $40,000 cap): SALT stays uncapped at $33,000 > $15,000 standard -> itemized.
        // At 2031 (post-sunset $10,000 cap): SALT capped to $10,000 < $15,000 standard -> standard.
        // The itemize/standard DECISION itself flips purely from the year-aware cap, and federal tax
        // is strictly lower in 2027 (bigger deduction) -- the conservative direction of this fix.
        StateTaxCalculator mockState = new FixedStateTaxCalculator("CA", bd("25000"));
        var combined = buildCombined(mockState, bd("8000"), BigDecimal.ZERO);

        CombinedTaxResult result2027 = combined.computeTax(bd("200000"), 2027, FilingStatus.SINGLE);
        CombinedTaxResult result2031 = combined.computeTax(bd("200000"), 2031, FilingStatus.SINGLE);

        assertThat(result2027.saltDeduction()).isEqualByComparingTo(bd("33000"));
        assertThat(result2027.itemizedDeductions()).isEqualByComparingTo(bd("33000"));
        assertThat(result2027.usedItemized()).isTrue();

        assertThat(result2031.saltDeduction()).isEqualByComparingTo(bd("10000"));
        assertThat(result2031.itemizedDeductions()).isEqualByComparingTo(bd("10000"));
        assertThat(result2031.usedItemized()).isFalse();

        // Taxable = 200,000 - 33,000 = 167,000 -> 1,192.50 + 4,386.00 + 12,072.50 + 15,276.00
        assertThat(result2027.federalTax()).isEqualByComparingTo(bd("32927.0000"));
        // Taxable = 200,000 - 15,000 = 185,000 -> 1,192.50 + 4,386.00 + 12,072.50 + 19,596.00
        assertThat(result2031.federalTax()).isEqualByComparingTo(bd("37247.0000"));
        assertThat(result2027.federalTax()).isLessThan(result2031.federalTax());
    }

    // === Age-65+ additional standard deduction threading (audit D) ===

    @Test
    void computeTax_birthYearAge66_boostsFederalDeductionAndLowersTax() {
        // Override the shared 2025 fixture with a nonzero age-65 addition for this test only
        // (the shared singleDeduction2025() fixture intentionally stays at additionalAge65=0).
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15000"), bd("2000"))));
        var combinedNoBirthYear = buildCombined(new NullStateTaxCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);
        var combinedAge66 = new CombinedTaxCalculator(federalCalc, new NullStateTaxCalculator(),
                BigDecimal.ZERO, BigDecimal.ZERO, 1959); // age 66 at tax year 2025

        var resultNoBirthYear = combinedNoBirthYear.computeTax(bd("60000"), 2025, FilingStatus.SINGLE);
        var resultAge66 = combinedAge66.computeTax(bd("60000"), 2025, FilingStatus.SINGLE);

        // No birth year: deduction 15,000, taxable 45,000 -> 1,192.50 + 33,075*0.12 = 5,161.50
        assertThat(resultNoBirthYear.federalTax()).isEqualByComparingTo(bd("5161.5000"));
        // Age 66: deduction 15,000+2,000=17,000, taxable 43,000 -> 1,192.50 + 31,075*0.12 = 4,921.50
        assertThat(resultAge66.federalTax()).isEqualByComparingTo(bd("4921.5000"));
        assertThat(resultAge66.federalTax()).isLessThan(resultNoBirthYear.federalTax());
    }

    @Test
    void computeTax_birthYearAge64_noBoostYet() {
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(2025, "single"))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15000"), bd("2000"))));
        var combinedNoBirthYear = buildCombined(new NullStateTaxCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);
        var combinedAge64 = new CombinedTaxCalculator(federalCalc, new NullStateTaxCalculator(),
                BigDecimal.ZERO, BigDecimal.ZERO, 1961); // age 64 at tax year 2025 -- one below the threshold

        var resultNoBirthYear = combinedNoBirthYear.computeTax(bd("60000"), 2025, FilingStatus.SINGLE);
        var resultAge64 = combinedAge64.computeTax(bd("60000"), 2025, FilingStatus.SINGLE);

        assertThat(resultAge64.federalTax()).isEqualByComparingTo(resultNoBirthYear.federalTax());
    }

    // === Household task 7: household-aware age-65 deduction threading (spec §4 step 6) ===

    @Test
    void computeTax_householdBothSpousesOver65Mfj_appliesDoubleAdder() {
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                        anyInt(), eq("married_filing_jointly")))
                .thenReturn(TaxBracketFixtures.mfj2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(2025, "married_filing_jointly"))
                .thenReturn(Optional.of(new StandardDeductionEntity(
                        2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        // Primary born 1958 (age 67 in 2025), spouse born 1958 - 2 = 1956 as well over 65 (age 69).
        var household = HouseholdContext.of(
                1958, 90, 1956, 90, 2065);
        var combinedHousehold = new CombinedTaxCalculator(federalCalc, new NullStateTaxCalculator(),
                BigDecimal.ZERO, BigDecimal.ZERO, null, household);
        var combinedNoHousehold = buildCombined(new NullStateTaxCalculator(), BigDecimal.ZERO, BigDecimal.ZERO);

        var resultHousehold = combinedHousehold.computeTax(
                bd("100000"), 2025, FilingStatus.MARRIED_FILING_JOINTLY);
        var resultBaseline = combinedNoHousehold.computeTax(
                bd("100000"), 2025, FilingStatus.MARRIED_FILING_JOINTLY);

        // The household result must be strictly lower (deduction boosted by 2 * 1,600 = 3,200) than
        // a baseline with no age-65 addition at all (the shared single2025 fixture stays at 0).
        assertThat(resultHousehold.federalTax()).isLessThan(resultBaseline.federalTax());
    }

    @Test
    void computeTax_householdPostTransitionSurvivorOnly_appliesSingleAdderNotDouble() {
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(2025, "married_filing_jointly"))
                .thenReturn(Optional.of(new StandardDeductionEntity(
                        2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("1600"))));
        // Primary (born 1953) dies at 72 in 2025 (both spouses were 65+ at that point); the survivor
        // (spouse, born 1956) is 69 that year.
        var household = HouseholdContext.of(
                1953, 72, 1956, 90, 2065);
        assertThat(household.transitionYear()).contains(2025);
        var combinedHousehold = new CombinedTaxCalculator(federalCalc, new NullStateTaxCalculator(),
                BigDecimal.ZERO, BigDecimal.ZERO, null, household);

        // The transition year files SINGLE (task 5's flip): confirm the wiring reproduces an
        // independent count-aware oracle call exactly -- and that the second qualifying age is null
        // (at most ONE adder), even though the now-dead primary would independently qualify by age.
        assertThat(household.secondFilerAgeIn(2025)).isNull();
        var actual = combinedHousehold.computeTax(bd("60000"), 2025, FilingStatus.SINGLE).federalTax();
        var oracle = federalCalc.computeTax(bd("60000"), 2025, FilingStatus.SINGLE,
                household.filerAgeIn(2025), household.secondFilerAgeIn(2025));
        assertThat(actual).isEqualByComparingTo(oracle);
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
