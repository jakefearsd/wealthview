package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
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
import com.wealthview.persistence.repository.LtcgBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
