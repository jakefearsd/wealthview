package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.LtcgBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.mfj2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Irmaa;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTaxAndIrmaa;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Household task 5: the first-death transition event and second-death truncation in the deterministic
 * engine. Pinned couple: primary born 1958 (dies 2043 at age 85), spouse born 1966. Survivor is the
 * SPOUSE. The transition fires exactly once at 2043 and, spec §4, switches Social Security to the
 * larger benefit (keep-larger), scales deceased-owned non-SS income by survivor_percent, rolls the
 * deceased's tax-advantaged accounts to the survivor, steps up the taxable basis, scales spending by
 * the survivor factor, and flips filing to single.
 */
class HouseholdTransitionTest extends DeterministicProjectionEngineTestSupport {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int PRIMARY_BIRTH = 1958;
    private static final int SPOUSE_BIRTH = 1966;
    private static final int TRANSITION_YEAR = 2043; // primary dies at age 85

    /** Household with the pinned couple; primary dies at {@code primaryDeathAge}, spouse at 95. */
    private static HouseholdContext household(int primaryDeathAge, int endAge) {
        return HouseholdContext.of(PRIMARY_BIRTH, primaryDeathAge, SPOUSE_BIRTH, 95, PRIMARY_BIRTH + endAge);
    }

    private static ProjectionAccountInput acct(String balance, String costBasis, String type, String owner) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US,
                Optional.empty(), bd(costBasis), type, owner);
    }

    private static ProjectionIncomeSourceInput ss(UUID id, String amount, int startAge, String owner) {
        return new ProjectionIncomeSourceInput(id, "SS-" + owner, IncomeSourceType.SOCIAL_SECURITY,
                bd(amount), startAge, null, ZERO, false, "taxable",
                null, null, null, null, null, null, owner, BigDecimal.ONE);
    }

    private static ProjectionIncomeSourceInput pension(String amount, int startAge, String owner,
                                                       String survivorPercent) {
        return new ProjectionIncomeSourceInput(UUID.randomUUID(), "Pension", IncomeSourceType.OTHER,
                bd(amount), startAge, null, ZERO, false, "taxable",
                null, null, null, null, null, null, owner, bd(survivorPercent));
    }

    private ProjectionInput input(int referenceYear, int endAge, String paramsJson,
                                  List<ProjectionAccountInput> accounts, SpendingProfileInput spending,
                                  List<ProjectionIncomeSourceInput> incomeSources, HouseholdContext household) {
        return new ProjectionInput(UUID.randomUUID(), "Household transition",
                LocalDate.of(2020, 1, 1), endAge, ZERO, paramsJson, accounts, spending,
                referenceYear, incomeSources, null, List.of(), household);
    }

    private static ProjectionYearDto yearOf(List<ProjectionYearDto> rows, int year) {
        return rows.stream().filter(r -> r.year() == year).findFirst().orElseThrow();
    }

    /** Coalesces a nullable DTO money field (the "positive-or-null" convention) to zero. */
    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : ZERO;
    }

    private static String mfjParams(String survivorFactor, boolean communityProperty) {
        return """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "withdrawal_rate": 0.04,
                 "withdrawal_order": "taxable_first", "fee_rate": 0,
                 "survivor_spending_factor": %s, "community_property": %b}
                """.formatted(PRIMARY_BIRTH, survivorFactor, communityProperty);
    }

    // === Transition step 1: Social Security keep-larger + deceased-owned non-SS × survivor_percent ===

    @Test
    void transition_primarySsLarger_survivorKeepsPrimarySsAndHalvesDeceasedPension() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var primarySs = ss(UUID.randomUUID(), "40000", 62, "primary");
        var spouseSs = ss(UUID.randomUUID(), "25000", 62, "spouse");
        var deceasedPension = pension("30000", 62, "primary", "0.5");

        var result = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("1000000", "1000000", "roth", "primary")),
                new SpendingProfileInput(bd("50000"), bd("10000"), null),
                List.of(primarySs, spouseSs, deceasedPension), household(85, 90)));

        // Pre-transition (both alive): both Social Security benefits + full pension.
        assertThat(yearOf(result.yearlyData(), 2042).incomeStreamsTotal())
                .isEqualByComparingTo(bd("95000")); // 40000 + 25000 + 30000

        // Transition year: survivor keeps the LARGER (primary's 40000) SS; spouse's 25000 ends; the
        // deceased-owned pension halves to 15000.
        var transitionRow = yearOf(result.yearlyData(), TRANSITION_YEAR);
        assertThat(transitionRow.incomeStreamsTotal()).isEqualByComparingTo(bd("55000")); // 40000 + 15000
        assertThat(transitionRow.incomeBySource()).containsKey(primarySs.id().toString());
        assertThat(transitionRow.incomeBySource()).doesNotContainKey(spouseSs.id().toString());
    }

    @Test
    void transition_spouseSsLarger_survivorKeepsSpouseSs_bothOrderings() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var primarySs = ss(UUID.randomUUID(), "25000", 62, "primary");
        var spouseSs = ss(UUID.randomUUID(), "40000", 62, "spouse");
        var deceasedPension = pension("30000", 62, "primary", "0.5");

        var result = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("1000000", "1000000", "roth", "primary")),
                new SpendingProfileInput(bd("50000"), bd("10000"), null),
                List.of(primarySs, spouseSs, deceasedPension), household(85, 90)));

        // Other ordering: the larger benefit is the SURVIVOR's own (spouse's 40000); primary's ends.
        var transitionRow = yearOf(result.yearlyData(), TRANSITION_YEAR);
        assertThat(transitionRow.incomeStreamsTotal()).isEqualByComparingTo(bd("55000")); // 40000 + 15000
        assertThat(transitionRow.incomeBySource()).containsKey(spouseSs.id().toString());
        assertThat(transitionRow.incomeBySource()).doesNotContainKey(primarySs.id().toString());
    }

    // === Transition step 5: filing flips to single, verified against an independent oracle ===

    @Test
    void transition_taxLiability_usesMfjBeforeAndSingleBracketsFromTransitionYear() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // A single pension (survivor_percent 1.0 so it does NOT change at transition) covers spending
        // -> the ONLY ordinary income each year is the 60000 pension, isolating the filing-status flip.
        var pension = pension("60000", 62, "primary", "1.0");
        var result = engine.run(input(2040, 90, mfjParams("1.0", false),
                List.of(acct("1000000", "1000000", "roth", "primary")),
                new SpendingProfileInput(bd("30000"), bd("10000"), null),
                List.of(pension), household(85, 90)));

        var oracle = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        // Pre-transition (both alive): MFJ brackets.
        assertThat(yearOf(result.yearlyData(), 2042).taxLiability())
                .isEqualByComparingTo(oracle.computeTax(bd("60000"), 2042, FilingStatus.MARRIED_FILING_JOINTLY));
        // Transition year onward: SINGLE brackets (independent oracle).
        assertThat(yearOf(result.yearlyData(), TRANSITION_YEAR).taxLiability())
                .isEqualByComparingTo(oracle.computeTax(bd("60000"), TRANSITION_YEAR, FilingStatus.SINGLE));
        // Sanity: the single bill is strictly higher than the MFJ bill on the same income.
        assertThat(oracle.computeTax(bd("60000"), TRANSITION_YEAR, FilingStatus.SINGLE))
                .isGreaterThan(oracle.computeTax(bd("60000"), 2042, FilingStatus.MARRIED_FILING_JOINTLY));
    }

    // === Task 7 (spec §4 step 6): per-person age-65 standard deduction while both spouses alive ===

    @Test
    void perPersonDeduction_bothSpousesOver65Mfj_engineMatchesCountAwareOracleThenSingleAdderAfter() {
        // Nonzero age-65 addition (the shared 2025 fixtures intentionally stay frozen at 0 -- see
        // CombinedTaxCalculatorTest's note). Primary (1958) is 65+ from 2023; spouse (1966) is 65+
        // from 2031 -- both well before the 2043 transition.
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                        anyInt(), eq("married_filing_jointly")))
                .thenReturn(mfj2025Brackets());
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(anyInt(), eq("married_filing_jointly")))
                .thenReturn(Optional.of(new StandardDeductionEntity(
                        2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("1600"))));
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);
        var oracle = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);

        // A single pension (survivor_percent 1.0 -> unaffected by the transition) covers spending,
        // isolating the deduction/filing-status effects from any income-side change.
        var pension = pension("60000", 62, "primary", "1.0");
        var result = engine.run(input(2040, 90, mfjParams("1.0", false),
                List.of(acct("1000000", "1000000", "roth", "primary")),
                new SpendingProfileInput(bd("30000"), bd("10000"), null),
                List.of(pension), household(85, 90)));

        // Pre-transition (2042): both alive, both 65+, MFJ -> deduction 31,500 + 2*1,600 = 34,700.
        assertThat(yearOf(result.yearlyData(), 2042).taxLiability())
                .isEqualByComparingTo(oracle.computeTax(bd("60000"), 2042,
                        FilingStatus.MARRIED_FILING_JOINTLY, 84, 76));
        // Post-transition (2043): survivor (spouse, 77) only -- SINGLE, exactly ONE adder even
        // though the now-dead primary would independently qualify by age.
        assertThat(yearOf(result.yearlyData(), TRANSITION_YEAR).taxLiability())
                .isEqualByComparingTo(oracle.computeTax(bd("60000"), TRANSITION_YEAR, FilingStatus.SINGLE, 77, null));
    }

    // === Task 7 (spec §4 step 6): per-person IRMAA surcharge while both spouses are alive ===

    @Test
    void irmaa_bothSpousesOver65MfjCrossingTier1_surchargeAppliesTwice() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025Irmaa(irmaaTierRepository);
        var engine = engineWithTaxAndIrmaa(taxBracketRepository, standardDeductionRepository, irmaaTierRepository);

        // Static other_income (no portfolio draws needed) keeps MAGI at a stable 240,000 every
        // year -- inside the MFJ tier-1 band (212,000-266,000, TaxBracketFixtures.mfj2025IrmaaTiers).
        var params = """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "withdrawal_rate": 0.04,
                 "withdrawal_order": "taxable_first", "fee_rate": 0, "other_income": 240000,
                 "survivor_spending_factor": 0.75, "community_property": false}
                """.formatted(PRIMARY_BIRTH);
        var result = engine.run(input(2029, 90, params,
                List.of(acct("100000", "100000", "roth", "primary")),
                new SpendingProfileInput(bd("1000"), bd("0"), null), List.of(), household(85, 90)));

        // 2031: primary (1958) is 73, spouse (1966) turns 65 -- both alive, both 65+, MFJ. The
        // 2-year MAGI lookback (year 2029) is already in-horizon by then.
        var year2031 = yearOf(result.yearlyData(), 2031);
        assertThat(year2031.irmaaSurcharge()).isEqualByComparingTo(bd("2104.80")); // 2*(74.00+13.70)*12
    }

    @Test
    void irmaa_postTransitionSurvivorOnly_surchargeAppliesOnceOnSingleTiers() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        stubSingle2025Irmaa(irmaaTierRepository);
        var engine = engineWithTaxAndIrmaa(taxBracketRepository, standardDeductionRepository, irmaaTierRepository);

        // 120,000 lands inside the SINGLE tier-1 band (106,000-133,000) but BELOW the MFJ tier-0
        // ceiling (212,000) -- pre-transition years owe no surcharge at all, isolating the
        // post-transition single-tier pin from any pre-transition MFJ contribution.
        var params = """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "withdrawal_rate": 0.04,
                 "withdrawal_order": "taxable_first", "fee_rate": 0, "other_income": 120000,
                 "survivor_spending_factor": 0.75, "community_property": false}
                """.formatted(PRIMARY_BIRTH);
        var result = engine.run(input(2029, 90, params,
                List.of(acct("100000", "100000", "roth", "primary")),
                new SpendingProfileInput(bd("1000"), bd("0"), null), List.of(), household(85, 90)));

        // 2045: primary died 2043 (transition); survivor (spouse, born 1966) is 79 -- alive, 65+,
        // filing SINGLE. Only the survivor counts (the deceased primary must NOT be double-counted).
        var year2045 = yearOf(result.yearlyData(), 2045);
        assertThat(year2045.irmaaSurcharge()).isEqualByComparingTo(bd("1052.40")); // 1*(74.00+13.70)*12
    }

    // === Task 7: SS convergence combines benefits + MFJ tiers pre-transition, survivor + single after ===

    @Test
    void socialSecurityTaxability_combinedMfjPreTransition_thenSurvivorSingleAfter() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);
        var ssOracle = new SocialSecurityTaxCalculator();

        // A modest static other_income lifts provisional income above the MFJ $32,000 / single
        // $25,000 no-tax floors so BOTH pinned oracle figures below are strictly positive (a zero
        // taxable-SS result serializes as null on the DTO -- the "positive-or-null" convention --
        // which would make this pin vacuous). Spending ($60,000) is set to exceed even the combined
        // SS cash ($50,000) so the year runs a genuine deficit draw (from the pure-Roth pool, itself
        // untaxed) rather than a cash surplus -- keeping the SS provisional-income base isolated to
        // exactly other_income (no surplus-branch base-tax feedback into the fixed point).
        var params = """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "withdrawal_rate": 0.04,
                 "withdrawal_order": "taxable_first", "fee_rate": 0, "other_income": 20000,
                 "survivor_spending_factor": 1.0, "community_property": false}
                """.formatted(PRIMARY_BIRTH);
        var primarySs = ss(UUID.randomUUID(), "30000", 62, "primary");
        var spouseSs = ss(UUID.randomUUID(), "20000", 62, "spouse");
        var result = engine.run(input(2029, 90, params,
                List.of(acct("500000", "500000", "roth", "primary")),
                new SpendingProfileInput(bd("60000"), bd("0"), null),
                List.of(primarySs, spouseSs), household(85, 90)));

        // Pre-transition (2035): BOTH SS benefits combine into ONE provisional-income computation
        // against the MFJ tiers (audit B2 / T3-1) -- pinned against an independent oracle call with
        // the COMBINED benefit and MARRIED_FILING_JOINTLY status. The pure-Roth pool realizes no
        // portfolio income, so the provisional-income base is just other_income (20,000) aside from
        // the SS benefit itself.
        var pre = yearOf(result.yearlyData(), 2035);
        var preOracle = ssOracle.computeTaxableAmount(
                bd("50000"), bd("20000"), "married_filing_jointly", 6, ZERO); // 30000+20000, exponent 2035-2029
        assertThat(pre.socialSecurityTaxable()).isEqualByComparingTo(preOracle);

        // Post-transition (2045): keep-larger already switched the survivor to the primary's larger
        // 30,000 benefit; taxability now runs SINGLE-status, single-benefit -- pinned against an
        // independent oracle call with SINGLE status and just the kept 30,000.
        var post = yearOf(result.yearlyData(), 2045);
        var postOracle = ssOracle.computeTaxableAmount(
                bd("30000"), bd("20000"), "single", 16, ZERO); // exponent 2045-2029
        assertThat(post.socialSecurityTaxable()).isEqualByComparingTo(postOracle);

        // Sanity: the two oracle figures are not coincidentally equal (proves the test actually
        // isolates the combined-vs-single distinction).
        assertThat(preOracle).isNotEqualByComparingTo(postOracle);
    }

    // === Task 7 (T5-review, spec §1): owner-age income windows while both spouses are alive ===

    @Test
    void ownerAgeWindow_spouseOwnedSource_startsAtSpouseAge_notPrimaryAge() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Spouse-owned pension starting at the SPOUSE's age 65 (spouse born 1966 -> turns 65 in
        // 2031). In 2029 the household is retired and both alive, but the spouse is only 63.
        var spouseSource = pension("24000", 65, "spouse", "1.0");
        var result = engine.run(input(2029, 90, mfjParams("1.0", false),
                List.of(acct("500000", "500000", "roth", "primary")),
                new SpendingProfileInput(bd("1000"), bd("0"), null),
                List.of(spouseSource), household(85, 90)));

        // 2029: spouse (1966) is 63 -- not yet active.
        assertThat(yearOf(result.yearlyData(), 2029).incomeStreamsTotal()).isEqualByComparingTo(ZERO);
        // 2032: spouse is 66 -- past the boundary, fully active.
        assertThat(yearOf(result.yearlyData(), 2032).incomeStreamsTotal()).isEqualByComparingTo(bd("24000"));
    }

    // === Transition step 3: basis step-up, visible via a later-year capital-gains-tax delta ===

    @Test
    void transition_jointTaxableStepUp_lowersLaterCapitalGainsTaxVersusNoStepUp() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var ltcgRepo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(ltcgRepo);
        stubMfj2025Ltcg(ltcgRepo);
        var engine = new DeterministicProjectionEngine(
                new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository), null,
                new CapitalGainsTaxCalculator(ltcgRepo));

        // Appreciated taxable pool (basis 400k, value 2M), spending drawn from it every year so a
        // FIFO sale realizes gain above the single 0% LTCG ceiling. JOINT ownership => 50% step-up at
        // first death; SPOUSE (survivor) ownership => 0% step-up (the no-step-up control). Higher
        // basis => less realized gain => less LTCG tax in a later year.
        var spending = new SpendingProfileInput(bd("150000"), bd("0"), null);
        var jointResult = engine.run(input(2040, 90, mfjParams("1.0", false),
                List.of(acct("2000000", "400000", "taxable", "joint")), spending, List.of(), household(85, 90)));
        var survivorOwnedResult = engine.run(input(2040, 90, mfjParams("1.0", false),
                List.of(acct("2000000", "400000", "taxable", "spouse")), spending, List.of(), household(85, 90)));

        // The joint run's later LTCG tax may be driven to zero (=> null) by the step-up; the
        // no-step-up control must still owe a strictly greater amount.
        BigDecimal jointLater = nz(yearOf(jointResult.yearlyData(), 2045).capitalGainsTax());
        BigDecimal noStepUpLater = nz(yearOf(survivorOwnedResult.yearlyData(), 2045).capitalGainsTax());
        assertThat(noStepUpLater).isGreaterThan(BigDecimal.ZERO);
        // The step-up reduces later realized gains, so its LTCG tax is strictly lower.
        assertThat(jointLater).isLessThan(noStepUpLater);
    }

    // === Transition step 4: spending scales by the survivor factor from the transition year ===

    @Test
    void transition_spending_scalesBySurvivorFactorFromTransitionYear() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Deficit years (no income, spending funded fully from the taxable pool). Compare a 0.75
        // survivor-factor run to a 1.0 run: pre-transition draws match; from the transition year the
        // survivor draws exactly 0.75x.
        var spending = new SpendingProfileInput(bd("100000"), bd("0"), null);
        var factorRun = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("3000000", "3000000", "taxable", "joint")), spending, List.of(), household(85, 90)));
        var fullRun = engine.run(input(2040, 90, mfjParams("1.0", false),
                List.of(acct("3000000", "3000000", "taxable", "joint")), spending, List.of(), household(85, 90)));

        // Pre-transition: identical spending (factor is 1.0 for both while both alive).
        assertThat(yearOf(factorRun.yearlyData(), 2042).withdrawals())
                .isEqualByComparingTo(yearOf(fullRun.yearlyData(), 2042).withdrawals());

        BigDecimal fullDraw = yearOf(fullRun.yearlyData(), TRANSITION_YEAR).withdrawals();
        BigDecimal factorDraw = yearOf(factorRun.yearlyData(), TRANSITION_YEAR).withdrawals();
        assertThat(fullDraw).isEqualByComparingTo(bd("100000"));
        assertThat(factorDraw).isEqualByComparingTo(bd("75000")); // 100000 * 0.75
    }

    // === Task 8 follow-up (T10 blocker): the viability/feasibility DISCLOSURES scale too ===
    // RetirementWithdrawalProcessor scales the actual draw (pinned above), but
    // SpendingFeasibilityAnalyzer.applyViability resolves the SAME plan through a second,
    // independent call site -- pre-fix it reported the UNSCALED essential/discretionary
    // post-transition, so spendingSurplus went (and stayed) negative and computeFeasibility
    // flagged a perfectly sustainable household plan infeasible. T10's probe scenario, pinned.

    @Test
    void transition_viabilityDisclosures_scaleBySurvivorFactorFromTransitionYear() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // T10 report's probe: joint taxable $3M (basis == balance, so no LTCG), flat profile
        // essential 60k / discretionary 20k, factor 0.75, no income sources, zero inflation.
        var spending = new SpendingProfileInput(bd("60000"), bd("20000"), null);
        var result = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("3000000", "3000000", "taxable", "joint")), spending, List.of(), household(85, 90)));

        // Pre-transition (2042): unscaled disclosures, zero surplus (withdrawals fully fund spending).
        var pre = yearOf(result.yearlyData(), 2042);
        assertThat(pre.essentialExpenses()).isEqualByComparingTo(bd("60000"));
        assertThat(pre.discretionaryExpenses()).isEqualByComparingTo(bd("20000"));
        assertThat(pre.withdrawals()).isEqualByComparingTo(bd("80000"));
        assertThat(pre.spendingSurplus()).isEqualByComparingTo(ZERO);

        // Transition year + every later year: disclosures scale x0.75 in lockstep with the draw,
        // so the surplus stays zero instead of reporting a phantom -20,000/yr shortfall.
        for (int year : new int[]{TRANSITION_YEAR, 2044}) {
            var row = yearOf(result.yearlyData(), year);
            assertThat(row.essentialExpenses()).as("essential %d", year).isEqualByComparingTo(bd("45000"));
            assertThat(row.discretionaryExpenses()).as("discretionary %d", year).isEqualByComparingTo(bd("15000"));
            assertThat(row.withdrawals()).as("withdrawals %d", year).isEqualByComparingTo(bd("60000"));
            assertThat(row.netSpendingNeed()).as("netSpendingNeed %d", year).isEqualByComparingTo(bd("60000"));
            assertThat(row.spendingSurplus()).as("spendingSurplus %d", year).isEqualByComparingTo(ZERO);
            assertThat(row.discretionaryAfterCuts()).as("discAfterCuts %d", year)
                    .isEqualByComparingTo(bd("15000"));
        }

        // The user-facing verdict: a sustainable household plan is FEASIBLE, no phantom shortfall
        // pinned at the transition year.
        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
        assertThat(result.spendingFeasibility().firstShortfallYear()).isNull();
    }

    @Test
    void transition_viabilityDisclosures_tieredProfile_scaledAndFeasible() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Same probe with a TIER-driven profile (the golden #6 shape): one open-ended tier from
        // age 62 overrides the base amounts. Tier resolution + survivor scaling must compose.
        var spending = new SpendingProfileInput(bd("999999"), bd("999999"), """
                [{"name": "Retirement", "start_age": 62, "end_age": null,
                  "essential_expenses": 60000, "discretionary_expenses": 20000}]
                """);
        var result = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("3000000", "3000000", "taxable", "joint")), spending, List.of(), household(85, 90)));

        var post = yearOf(result.yearlyData(), 2044);
        assertThat(post.essentialExpenses()).isEqualByComparingTo(bd("45000"));
        assertThat(post.discretionaryExpenses()).isEqualByComparingTo(bd("15000"));
        assertThat(post.spendingSurplus()).isEqualByComparingTo(ZERO);
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
    }

    // === Task 8 follow-up #2 (second T10 blocker): survivor income nets against SCALED spending ===
    // The draw must be max(0, factor×N − I), not factor×(N − I): scaling the plan's pre-netted
    // difference credits survivor income at only factor× (over-draw = (1−factor)×I per year) and,
    // in the window factor×N < I < N, BOTH draws and deposits a surplus the same year. The MC
    // engine already nets after scaling (floors pre-scaled at the OptimizationContextBuilder choke
    // point, then resolveSpendingFunding nets income at 100%) — these pins restore engine parity.

    @Test
    void transition_survivorIncome_creditsFullyAgainstScaledSpending_notScaledNetDraw() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // T10 probe 1 (deficit region): survivor-kept pension 30k, essential 60k, factor 0.75.
        // Coherent post-transition draw = 0.75×60,000 − 30,000 = 15,000 (pre-fix: 22,500).
        var survivorPension = pension("30000", 62, "spouse", "1.0");
        var result = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("3000000", "3000000", "taxable", "joint")),
                new SpendingProfileInput(bd("60000"), bd("0"), null),
                List.of(survivorPension), household(85, 90)));

        // Pre-transition: draw = 60,000 − 30,000 (MFJ tax on the 30k pension is 0), surplus 0.
        var pre = yearOf(result.yearlyData(), 2042);
        assertThat(pre.withdrawals()).isEqualByComparingTo(bd("30000"));
        assertThat(pre.spendingSurplus()).isEqualByComparingTo(ZERO);

        for (int year : new int[]{TRANSITION_YEAR, 2044}) {
            var row = yearOf(result.yearlyData(), year);
            assertThat(row.withdrawals()).as("withdrawals %d", year).isEqualByComparingTo(bd("15000"));
            assertThat(row.essentialExpenses()).as("essential %d", year).isEqualByComparingTo(bd("45000"));
            // Coherence identity: available (draw + income) EXACTLY funds the scaled spending —
            // surplus + taxLiability == 0. (The residual surplus of −tax is the pre-existing
            // deficit-year convention for base-income tax — funded from the pools outside the
            // `withdrawals` figure — not a household artifact; pre-fix this identity broke by the
            // phantom (1−0.75)×30,000 = +7,500 over-draw.)
            assertThat(nz(row.spendingSurplus()).add(row.taxLiability()))
                    .as("surplus + tax %d", year).isEqualByComparingTo(ZERO);
            assertThat(nz(row.surplusReinvested())).as("surplusReinvested %d", year).isEqualByComparingTo(ZERO);
        }
    }

    @Test
    void transition_incomeInsideScaledUnscaledWindow_depositsSurplusWithoutSimultaneousDraw() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // T10 probe 2 (window region): pension 55k sits between scaled (45k) and unscaled (60k)
        // spending. Coherent: draw 0, deposit the after-tax surplus. Pre-fix the engine BOTH drew
        // 0.75×(60,000−55,000) = 3,750 AND deposited a surplus the same year — a double-flow
        // through the pools a coherent model never produces.
        var survivorPension = pension("55000", 62, "spouse", "1.0");
        var result = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("3000000", "3000000", "taxable", "joint")),
                new SpendingProfileInput(bd("60000"), bd("0"), null),
                List.of(survivorPension), household(85, 90)));

        for (int year : new int[]{TRANSITION_YEAR, 2044}) {
            var row = yearOf(result.yearlyData(), year);
            assertThat(row.withdrawals()).as("withdrawals %d", year).isEqualByComparingTo(ZERO);
            assertThat(nz(row.surplusReinvested())).as("surplusReinvested %d", year).isPositive();
        }
    }

    @Test
    void transition_deterministicAndMcShareTheNetAfterScalingRule() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Cross-engine parity on the same year-shape: scaled spending 45,000, income 30,000,
        // taxable-only pool, no growth/tax. Deterministic: exact BigDecimal 15,000. MC: floors
        // arrive PRE-scaled from OptimizationContextBuilder's choke point and the trial nets
        // income at 100% (resolveSpendingFunding: max(0, spending − income)) — same rule; the MC
        // half is asserted at double precision within 1e-6 (the documented tolerance).
        var survivorPension = pension("30000", 62, "spouse", "1.0");
        var det = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("3000000", "3000000", "taxable", "joint")),
                new SpendingProfileInput(bd("60000"), bd("0"), null),
                List.of(survivorPension), household(85, 90)));
        assertThat(yearOf(det.yearlyData(), 2044).withdrawals()).isEqualByComparingTo(bd("15000"));

        var simulator = new TrialSimulator();
        var config = new TrialSimulator.SimulationConfig(
                3_000_000.0, 0.0, 0.0, "taxable_first", null, null, null, null, 78, null,
                0, 0.0, false, new double[]{0.0}, new double[]{0.0}, new double[]{0.0},
                Integer.MAX_VALUE, 3_000_000.0, null, 0.0);
        var trial = simulator.simulateTrial(new double[]{30_000.0}, new double[]{0.0},
                new double[]{45_000.0}, new double[]{0.0}, 1, config);
        assertThat(trial.finalBalance()).isEqualTo(3_000_000.0 - 15_000.0, within(1e-6));
    }

    @Test
    void transition_guardrailSchedule_consumedAsIs_neverRescaledBySurvivorFactor() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // A frozen guardrail/optimizer schedule is ALREADY survivor-scaled end-to-end at
        // optimization time (T6 pre-scales the floors, T8 the reported discretionary, and the
        // income splice is household-aware). The deterministic engine must consume it at factor
        // 1.0 — re-applying the year factor would double-scale (0.75² = 0.5625×). Post-transition
        // rows here carry the optimizer's own scaled/netted numbers: recommended 45,000
        // (= 60,000×0.75), floor 30,000, discretionary 15,000, portfolioWithdrawal 15,000 (netted
        // against the optimizer's survivor income 30,000).
        var rows = new ArrayList<GuardrailYearlySpending>();
        for (int y = 2040; y <= 2047; y++) {
            boolean post = y >= TRANSITION_YEAR;
            rows.add(new GuardrailYearlySpending(
                    y, y - PRIMARY_BIRTH,
                    post ? bd("45000") : bd("60000"), bd("20000"), bd("90000"),
                    post ? bd("30000") : bd("40000"), post ? bd("15000") : bd("20000"),
                    bd("30000"), post ? bd("15000") : bd("30000"), "Retirement"));
        }
        var survivorPension = pension("30000", 62, "spouse", "1.0");
        var guardrailInput = new GuardrailSpendingInput(rows);
        var projectionInput = new ProjectionInput(UUID.randomUUID(), "Household guardrail",
                LocalDate.of(2020, 1, 1), 90, ZERO, mfjParams("0.75", false),
                List.of(acct("3000000", "3000000", "taxable", "joint")),
                null, 2040, List.of(survivorPension), guardrailInput, List.of(), household(85, 90));

        var result = engine.run(projectionInput);

        // Pre-transition row consumed as-is (factor 1.0 both alive — sanity, not the fix).
        assertThat(yearOf(result.yearlyData(), 2042).withdrawals()).isEqualByComparingTo(bd("30000"));
        // Post-transition: the frozen schedule's own numbers — NOT ×0.75 again (draw 11,250 /
        // essential 22,500 would be the double-scale).
        var row = yearOf(result.yearlyData(), 2044);
        assertThat(row.withdrawals()).isEqualByComparingTo(bd("15000"));
        assertThat(row.essentialExpenses()).isEqualByComparingTo(bd("30000"));
        assertThat(row.discretionaryExpenses()).isEqualByComparingTo(bd("15000"));
    }

    // === HP3 Part A: end-to-end interaction pin -- filing-flip x per-person age-65 deduction AND
    // the HP1 exact per-owner basis step-up, together, through ONE full projection. Feature-review
    // gap: golden #6 is all-joint taxable (blended == exact there) and mocks the age-65 adder to
    // zero, so neither the HP1 exact-vs-blended delta nor the age-65-adder x filing-flip interaction
    // had ever been proven end-to-end (only via unit tests / the pool-level MultiPoolOwnerTest).

    @Test
    void householdEndToEnd_filingFlipAgeDeductionAndExactStepUp_composeThroughFullProjection() {
        // Nonzero age-65 adder (the shared 2025 fixtures stay frozen at 0) -- same stub shape as
        // perPersonDeduction_bothSpousesOver65Mfj...Test.
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                        anyInt(), eq("married_filing_jointly")))
                .thenReturn(mfj2025Brackets());
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(anyInt(), eq("married_filing_jointly")))
                .thenReturn(Optional.of(new StandardDeductionEntity(
                        2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        lenient().when(standardDeductionRepository.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("1600"))));
        var ltcgRepo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(ltcgRepo);
        stubMfj2025Ltcg(ltcgRepo);
        var federalTaxCalculator = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        var engine = new DeterministicProjectionEngine(federalTaxCalculator, null,
                new CapitalGainsTaxCalculator(ltcgRepo));

        // Spouse predeceases at 70 (dies 2036, primary age 78); primary (survivor) dies at 95 --
        // beyond the horizon (2048), so exactly one transition and no truncation. Both spouses are
        // 65+ well before the transition (primary since 2023, spouse since 2031) and the survivor
        // (primary) stays 65+ after it.
        int spouseDeathAge = 70;
        int transitionYear = SPOUSE_BIRTH + spouseDeathAge; // 2036
        var household = HouseholdContext.of(PRIMARY_BIRTH, 95, SPOUSE_BIRTH, spouseDeathAge, PRIMARY_BIRTH + 90);
        assertThat(household.transitionYear()).contains(transitionYear);
        assertThat(household.secondDeathYear()).isEmpty();

        // Mixed-ownership taxable pool (HP1): joint gain-HEAVY (100k value / 20k basis, seeded
        // first/oldest) + spouse-owned gain-LIGHT (100k value / 90k basis -- the decedent's own
        // lot). Common-law joint rate 0.5. Zero growth/dividend/interest (below) keeps both lots at
        // their seeded value until the deliberate full liquidation pinned below.
        List<ProjectionAccountInput> accounts = List.of(
                acct("100000", "20000", "taxable", "joint"),
                acct("100000", "90000", "taxable", "spouse"));

        // Primary-owned (survivor-owned) pension, unaffected by the transition -- deliberately
        // straddles the MFJ 1-adder (33,100) / 2-adder (34,700) deduction boundary, so a correct
        // BOTH-alive MFJ deduction taxes it at exactly zero while a (wrong) single-adder deduction
        // would not -- proving the "2x" per-person count, not just "some deduction applies".
        var pension = pension("34000", 50, "primary", "1.0");

        // Tiered spending: flat 34,000 (== pension, zero net need) through age 77, then a deliberate
        // spike to 500,000 from age 78 (primary's age at the 2036 transition) -- the FIRST-EVER draw
        // on the taxable pool is therefore a single, complete liquidation exactly in the transition
        // year, realizing the step-up-adjusted gain in one FIFO sweep.
        var spending = new SpendingProfileInput(bd("999999"), ZERO, """
                [{"name": "PreTransition", "start_age": 0, "end_age": 77,
                  "essential_expenses": 34000, "discretionary_expenses": 0},
                 {"name": "PostTransition", "start_age": 78, "end_age": null,
                  "essential_expenses": 500000, "discretionary_expenses": 0}]
                """);

        String params = """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "withdrawal_rate": 0.04,
                 "withdrawal_order": "taxable_first", "fee_rate": 0, "dividend_yield": 0,
                 "interest_yield": 0, "survivor_spending_factor": 1.0, "community_property": false}
                """.formatted(PRIMARY_BIRTH);

        // Loop starts in 2031 (not earlier) -- the year the SPOUSE first turns 65 (born 1966): before
        // that, only the primary's single adder would apply (deduction 33,100 < 34,000), owing a
        // small tax that -- per PoolStrategy#deductFromPools -- draws taxable-first, FIFO, from the
        // very lot this fixture needs pristine until the deliberate transition-year liquidation.
        var result = engine.run(input(2031, 90, params, accounts, spending, List.of(pension), household));

        // === Pin 1: age-65 deduction while both alive (MFJ, 2x adder) -- taxes the 34,000 pension
        // at exactly zero (34,000 <= 31,500 + 2*1,600 = 34,700), and the pool is untouched (no LTCG).
        var preRow = yearOf(result.yearlyData(), 2033); // primary 75, spouse 67: both alive, both 65+
        assertThat(nz(preRow.taxLiability()))
                .isEqualByComparingTo(federalTaxCalculator.computeTax(
                        bd("34000"), 2033, FilingStatus.MARRIED_FILING_JOINTLY, 75, 67));
        assertThat(nz(preRow.taxLiability())).isEqualByComparingTo(ZERO);
        // Discriminates the "2x": with only ONE qualifying age, the SAME income would owe tax
        // (31,500 + 1*1,600 = 33,100 < 34,000) -- proving the engine applied BOTH adders, not one.
        assertThat(federalTaxCalculator.computeTax(bd("34000"), 2033, FilingStatus.MARRIED_FILING_JOINTLY, 75, 40))
                .isGreaterThan(ZERO);
        assertThat(nz(preRow.capitalGainsTax())).isEqualByComparingTo(ZERO);

        // === Pin 2: filing flip + per-person deduction, survivor phase (SINGLE, 1x adder). Isolate
        // the ordinary-tax component by subtracting the (already-folded-in) capitalGainsTax -- the
        // same technique golden #6's sanity review uses to isolate the filing-status bill.
        var transitionRow = yearOf(result.yearlyData(), transitionYear);
        BigDecimal ordinaryTax = transitionRow.taxLiability().subtract(nz(transitionRow.capitalGainsTax()));
        assertThat(ordinaryTax).isEqualByComparingTo(federalTaxCalculator.computeTax(
                bd("34000"), transitionYear, FilingStatus.SINGLE, 78, null));
        // Discriminates the "1x" (vs. 0 adders, i.e. under 65): a different bill on the same income.
        assertThat(ordinaryTax).isNotEqualByComparingTo(federalTaxCalculator.computeTax(
                bd("34000"), transitionYear, FilingStatus.SINGLE, 40, null));

        // === Pin 3 (HP1 e2e): the realized LTCG reflects the EXACT per-owner step-up (joint 0.5,
        // spouse-owned-decedent 1.0 -> 40,000 gain), not the retired blended-factor approximation
        // (0.75 uniformly -> 22,500 gain). Independent oracle: CapitalGainsTaxCalculator, the SAME
        // primitive the engine itself calls, fed the two candidate gain figures directly -- not the
        // engine's own annotated output. LTCG stacks on ordinary (34,000 - 15,750 base single
        // deduction = 18,250 floor; the LTCG stacking floor is base-deduction-only, a documented
        // pre-existing simplification -- see PoolStrategy#resolveOrdinaryDeduction).
        var ltcgOracle = new CapitalGainsTaxCalculator(ltcgRepo);
        BigDecimal exactGainTax = ltcgOracle.computeLtcgTax(bd("18250"), bd("40000"), transitionYear,
                FilingStatus.SINGLE, transitionYear - 2031, ZERO, bd("74000"));
        BigDecimal blendedGainTax = ltcgOracle.computeLtcgTax(bd("18250"), bd("22500"), transitionYear,
                FilingStatus.SINGLE, transitionYear - 2031, ZERO, bd("56500"));
        assertThat(exactGainTax).isNotEqualByComparingTo(blendedGainTax); // sanity: not coincidentally equal
        assertThat(exactGainTax).isEqualByComparingTo(bd("1485.00"));     // hand-derived: 30,100@0% + 9,900@15%
        assertThat(blendedGainTax).isEqualByComparingTo(ZERO);            // hand-derived: 22,500 fully @0%

        assertThat(transitionRow.capitalGainsTax()).isEqualByComparingTo(exactGainTax);
        assertThat(transitionRow.capitalGainsTax()).isNotEqualByComparingTo(blendedGainTax);
    }

    // === Idempotence: the transition fires exactly once, even across the SS convergence re-runs ===

    @Test
    void transition_withActiveSocialSecurityConvergence_balanceIdentityHoldsAndTotalIsContinuous() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Active Social Security in the transition year drives the B2 convergence loop (multiple
        // snapshot/restore passes). Taxable + Roth only (no RMD/reinvestment) keeps a clean balance
        // identity: a deficit year means every draw is a real outflow. If the transition were
        // re-applied or reverted across the convergence passes, value would leak and the identity
        // would break. (Rollover-specific idempotence is pinned at the pool level in
        // MultiPoolOwnerTest#applyFirstDeathTransition_thenSnapshotRestore_preservesTransitionExactlyOnce.)
        var primarySs = ss(UUID.randomUUID(), "45000", 62, "primary");
        var spouseSs = ss(UUID.randomUUID(), "30000", 62, "spouse");
        var result = engine.run(input(2040, 90, mfjParams("0.75", false),
                List.of(acct("2000000", "2000000", "taxable", "joint"),
                        acct("500000", "500000", "roth", "primary")),
                new SpendingProfileInput(bd("90000"), bd("20000"), null),
                List.of(primarySs, spouseSs), household(85, 90)));

        var transitionRow = yearOf(result.yearlyData(), TRANSITION_YEAR);
        // Balance identity across the transition boundary: nothing created or destroyed even as the
        // Social Security fixed point re-runs and the transition (applied once, before the snapshot)
        // is preserved by every restore.
        BigDecimal expectedEnd = transitionRow.startBalance().add(transitionRow.contributions())
                .add(transitionRow.growth()).subtract(transitionRow.withdrawals())
                .subtract(transitionRow.taxLiability());
        assertThat(transitionRow.endBalance()).isEqualByComparingTo(expectedEnd);

        // Continuity: the transition conserves total value, so the transition year's opening balance
        // equals the prior year's closing balance exactly.
        assertThat(transitionRow.startBalance())
                .isEqualByComparingTo(yearOf(result.yearlyData(), 2042).endBalance());
        // The SS convergence actually engaged (a taxable SS figure was resolved), and keep-larger
        // switched the survivor to the single larger benefit.
        assertThat(transitionRow.socialSecurityTaxable()).isNotNull();
        assertThat(transitionRow.incomeBySource()).containsKey(primarySs.id().toString());
        assertThat(transitionRow.incomeBySource()).doesNotContainKey(spouseSs.id().toString());
    }

    // === Anchors: single-person and both-die-beyond-horizon are byte-identical to the no-household path ===

    @Test
    void bothDeathsBeyondHorizon_producesByteIdenticalResultToNoHouseholdRun() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        String params = mfjParams("0.75", false);
        List<ProjectionAccountInput> accounts = List.of(
                acct("500000", "0", "traditional", "primary"),
                acct("300000", "300000", "taxable", "primary"));
        var spending = new SpendingProfileInput(bd("40000"), bd("10000"), null);

        // Both die well beyond the horizon (death ages 100/100) => no transition, no truncation.
        var beyondHorizon = HouseholdContext.of(PRIMARY_BIRTH, 100, SPOUSE_BIRTH, 100, PRIMARY_BIRTH + 80);
        var householdResult = engine.run(input(2035, 80, params, accounts, spending, List.of(), beyondHorizon));
        var noHouseholdResult = engine.run(input(2035, 80, params, accounts, spending, List.of(), null));

        assertThat(householdResult.yearlyData()).isEqualTo(noHouseholdResult.yearlyData());
        assertThat(householdResult.finalBalance()).isEqualByComparingTo(noHouseholdResult.finalBalance());
    }

    @Test
    void singlePersonHousehold_runsFullHorizonWithNoTransition() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var single = HouseholdContext.single(PRIMARY_BIRTH);
        var result = engine.run(input(2035, 90,
                """
                {"birth_year": %d, "filing_status": "single", "withdrawal_rate": 0.04, "fee_rate": 0}
                """.formatted(PRIMARY_BIRTH),
                List.of(acct("800000", "0", "traditional", "primary")),
                new SpendingProfileInput(bd("40000"), bd("10000"), null), List.of(), single));

        // endYear = 1958 + 90 = 2048; loop runs [2035, 2048) = 13 rows.
        assertThat(result.yearlyData()).hasSize(2048 - 2035);
        assertThat(result.yearlyData().getLast().year()).isEqualTo(2047);
    }

    // === Transition step 6 (truncation): the projection ends at the second death within the horizon ===

    @Test
    void secondDeathWithinHorizon_truncatesResultAtSecondDeathYearWithBequestBalance() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Primary dies 2040 (age 82); spouse (survivor) dies 2050 (age 84). Horizon end 2053.
        var truncating = HouseholdContext.of(PRIMARY_BIRTH, 82, SPOUSE_BIRTH, 84, PRIMARY_BIRTH + 95);
        assertThat(truncating.secondDeathYear()).contains(2050);

        var result = engine.run(input(2035, 95, mfjParams("0.75", false),
                List.of(acct("1500000", "0", "traditional", "primary")),
                new SpendingProfileInput(bd("50000"), bd("10000"), null), List.of(), truncating));

        // Loop runs [2035, 2051) => last projected year is 2050 (the second death), 16 rows.
        assertThat(result.yearlyData()).hasSize(2050 - 2035 + 1);
        var lastRow = result.yearlyData().getLast();
        assertThat(lastRow.year()).isEqualTo(2050);
        // Final balance is the bequest = the last projected year's ending balance.
        assertThat(result.finalBalance()).isEqualByComparingTo(lastRow.endBalance());
    }
}
