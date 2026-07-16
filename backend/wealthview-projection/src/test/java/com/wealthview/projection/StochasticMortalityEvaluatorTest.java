package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.mortality.MortalityTable;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.mfj2025Brackets;
import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Sub-project B (stochastic mortality), task 6: end-to-end pins for the SEPARATE stochastic evaluation
 * pass ({@link MonteCarloSpendingOptimizer#evaluateStochasticMortality} → {@link
 * StochasticMortalityEvaluator}). The recommendation ({@code optimize}) is unchanged; this pass runs the
 * trials over the fixed-death-optimized schedule with per-trial sampled deaths + the three-regime splice
 * and returns the raw per-trial success + sampled death ages (the task-7 output contract).
 *
 * <p>Task 7 additions live at the bottom of this class (reusing the same fixtures): pins that {@link
 * MonteCarloSpendingOptimizer#optimizeInternal} attaches a non-null {@code StochasticMortalitySummary}
 * for a stochastic household and null for toggle-off, plus the ANCHOR proof that folding the evaluation
 * into {@code optimizeInternal} does not move the fixed-death recommendation.
 *
 * <p>A household with SS income for BOTH spouses exercises the survivor keep-larger income splice (the
 * survivor loses the smaller benefit, raising withdrawals), so the joint→survivor regime assembly is
 * economically live even though this optimizer is built without a federal tax calculator.
 */
class StochasticMortalityEvaluatorTest {

    private final MonteCarloSpendingOptimizer optimizer =
            new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

    // === basic: the pass produces per-trial success + the sampled death ages straight from the draws ===

    @Test
    void evaluateStochasticMortality_household_producesPerTrialSuccessAndSampledDeathAges() {
        // Primary male dies 82 (2044, index 14 -> first death), spouse female dies 90 (2058, beyond the
        // 2052 horizon -> survivor). Fully-tabulated step table forces those exact ages every trial.
        var eval = optimizer.evaluateStochasticMortality(
                stochasticInput(42L, 82, 90, stepTable(82, 90)));

        assertThat(eval).isNotNull();
        assertThat(eval.success()).hasSize(300);
        assertThat(eval.firstDeathAge()).hasSize(300).containsOnly(82);   // primary dies first
        assertThat(eval.secondDeathAge()).hasSize(300).containsOnly(90);  // spouse survives
        long successes = countTrue(eval.success());
        assertThat(successes).isBetween(0L, 300L); // a real fraction was computed, not a crash/no-op
    }

    // === determinism: same seed -> identical evaluation (the mortality rng is seeded off input.seed) ===

    @Test
    void evaluateStochasticMortality_sameSeed_isReproducible() {
        var a = optimizer.evaluateStochasticMortality(stochasticInput(7L, 82, 90, stepTable(82, 90)));
        var b = optimizer.evaluateStochasticMortality(stochasticInput(7L, 82, 90, stepTable(82, 90)));

        assertThat(b.success()).isEqualTo(a.success());
        assertThat(b.firstDeathAge()).isEqualTo(a.firstDeathAge());
        assertThat(b.secondDeathAge()).isEqualTo(a.secondDeathAge());
    }

    // === degenerate cross-check: forcing the fixed death ages makes the stochastic success match the
    //     fixed-death recommendation's headline success (validates joint+regime+splice reproduces A) ===

    @Test
    void evaluateStochasticMortality_forcingFixedDeathAges_matchesFixedDeathHeadlineSuccess() {
        // Explicit fixed death ages 82/86 AND a table forcing exactly 82/86 every trial. The recommendation
        // (optimize) prices the fixed death; the stochastic pass prices the SAME (degenerate) death per
        // trial via the joint arrays + regime splice + in-loop factor. The two success numbers must agree.
        var input = stochasticInput(99L, 82, 86, stepTable(82, 86));
        double fixedHeadline = optimizer.optimize(input).successProbability().doubleValue();

        var eval = optimizer.evaluateStochasticMortality(input);
        double stochasticSuccess = (double) countTrue(eval.success()) / eval.success().length;

        // Same underlying economics -> the rates coincide (allow a hair for any capacity-clamp edge).
        assertThat(stochasticSuccess).isCloseTo(fixedHeadline, org.assertj.core.data.Offset.offset(0.02));
    }

    // === transition-at-retirement (index 0) corner: first death lands at year 0, exercising the
    //     year-0 seed/scale path flagged (correct-but-unexercised) in the Commit-A review ===

    @Test
    void evaluateStochasticMortality_firstDeathAtRetirement_runsWithIndexZeroTransition() {
        // Primary male dies at 68 = his retirement age (2030 = MC year 0) -> transition index 0; spouse
        // female survives to 90. The whole modeled horizon is survivor-phase from year 0, so the year-0
        // seed/scale path (survivor factor applied at index 0) is exercised.
        var eval = optimizer.evaluateStochasticMortality(
                stochasticInput(3L, 68, 90, stepTable(68, 90)));

        assertThat(eval).isNotNull();
        assertThat(eval.firstDeathAge()).containsOnly(68);  // primary dies at retirement
        assertThat(eval.secondDeathAge()).containsOnly(90);
        assertThat(eval.success()).hasSize(300); // ran to completion through the index-0 transition

        // Value pin (not just smoke): the index-0 run's success is DISTINCT from an otherwise-identical
        // run whose first death lands well inside the horizon (primary dies 82 -> transition index 14).
        // With 14 fewer survivor-phase years (and no year-0 seed scaling) the two success rates diverge,
        // so a regression in the year-0 transition/seed path would collapse this difference.
        var laterEval = optimizer.evaluateStochasticMortality(
                stochasticInput(3L, 82, 90, stepTable(82, 90)));
        assertThat(countTrue(eval.success())).isNotEqualTo(countTrue(laterEval.success()));
    }

    // === guard: a non-stochastic run has no longevity number to produce ===

    @Test
    void evaluateStochasticMortality_nonStochasticRun_returnsNull() {
        // Same household but stochasticMortality left off (no table) -> the recommendation-only path.
        var input = stochasticInput(42L, 82, 90, null); // null table + toggle off below

        assertThat(optimizer.evaluateStochasticMortality(input)).isNull();
    }

    // === task 7: the folded production path attaches the aggregated summary in the SAME optimizeInternal
    //     pass, reusing this class's stochastic household fixture (no second evaluate() re-run) ===

    @Test
    void optimizeInternal_stochasticHousehold_attachesNonNullSummary() {
        var result = optimizer.optimizeInternal(stochasticInput(42L, 82, 90, stepTable(82, 90)));

        assertThat(result.stochasticMortality()).isNotNull();
        assertThat(result.stochasticMortality().lifetimeSuccessProbability()).isBetween(0.0, 1.0);
        assertThat(result.stochasticMortality().secondDeathAge().median()).isEqualTo(90);
    }

    @Test
    void optimizeInternal_toggleOff_attachesNullSummary() {
        var result = optimizer.optimizeInternal(stochasticInput(42L, 82, 90, null));

        assertThat(result.stochasticMortality()).isNull();
    }

    // === task 7 ANCHOR: folding the evaluation into optimizeInternal must not move the fixed-death
    //     recommendation -- toggle on vs toggle off (otherwise-identical inputs) must be byte-identical,
    //     exactly as sub-project B's own "null/false stochasticMortality is byte-identical to A" contract
    //     already requires. A regression here would mean the read-only evaluation pass leaked back into
    //     the recommendation, which is the one thing this fold must never do. ===

    @Test
    void optimize_stochasticToggleOn_recommendationByteIdenticalToToggleOff() {
        var toggleOff = stochasticInput(42L, 82, 90, null);
        var toggleOn = stochasticInput(42L, 82, 90, stepTable(82, 90));

        var off = optimizer.optimize(toggleOff);
        var on = optimizer.optimize(toggleOn);

        assertThat(on.yearlySpending()).isEqualTo(off.yearlySpending());
        assertThat(on.successProbability()).isEqualByComparingTo(off.successProbability());
        assertThat(on.medianFinalBalance()).isEqualByComparingTo(off.medianFinalBalance());
        assertThat(on.failureRate()).isEqualByComparingTo(off.failureRate());
        assertThat(on.percentile10Final()).isEqualByComparingTo(off.percentile10Final());
    }

    // === IMPORTANT: per-identity survivor age drives the age-65 deduction in the RIGHT regime's table ===
    // Built with a REAL FederalTaxCalculator (age-65 additional standard deduction LIVE, unlike the
    // all-zero-table optimizer above), through the actual OptimizationContextBuilder regime path. A
    // copy-paste bug that used the primary's age for the spouse-survivor regime (or vice versa) would
    // vanish the asserted gap -- verified RED by temporarily swapping the survivor PersonId.

    @Test
    void buildSurvivorRegime_perIdentityAge_appliesAge65DeductionToTheCorrectSurvivor() {
        // Age-gap household: primary born 1970 (retires 2030 at 60, so BELOW 65 in year 0), spouse born
        // 1958 (age 72 in 2030, ABOVE 65). At MC year 0 the age-65 additional deduction ($2,000 single)
        // applies to the SPOUSE-survivor regime but NOT the PRIMARY-survivor regime.
        var ctx = new OptimizationContextBuilder(age65AwareCalc(), null)
                .build(ageGapStochasticInput(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        var arrays = ctx.sim().stochasticEval();
        assertThat(arrays).isNotNull();
        OrdinaryTaxTable primaryY0 =
                arrays.survivorRegimes()[TrialSimulator.PRIMARY_SURVIVES].ordinaryTaxTableByYear()[0];
        OrdinaryTaxTable spouseY0 =
                arrays.survivorRegimes()[TrialSimulator.SPOUSE_SURVIVES].ordinaryTaxTableByYear()[0];

        // Probe $60,000: primary (age 60, deduction $15,750) taxable 44,250; spouse (age 72, deduction
        // $17,750) taxable 42,250 -- both inside the single-2025 12% bracket, so the spouse's extra
        // $2,000 age-65 deduction saves exactly $2,000 * 12% = $240. Swapping the survivor age makes both
        // tables use the same age and this gap collapses to $0.
        double primaryTax = primaryY0.taxAt(60_000);
        double spouseTax = spouseY0.taxAt(60_000);
        assertThat(spouseTax).isCloseTo(4831.50, within(1e-6));      // age-65 (age 72) single deduction
        assertThat(primaryTax - spouseTax).isCloseTo(240.0, within(1e-6)); // ONLY the spouse gets the adder
    }

    // --- fixtures ---

    /** A REAL FederalTaxCalculator over mocked repos with the age-65 additional standard deduction LIVE
     * (single +$2,000, MFJ +$1,600 per qualifying person) so the survivor's age genuinely moves the
     * per-year table -- unlike {@code new MonteCarloSpendingOptimizer(null, ...)}, whose null calculator
     * yields all-zero tables and a dead age path. */
    private static FederalTaxCalculator age65AwareCalc() {
        var brackets = mock(TaxBracketRepository.class);
        var deductions = mock(StandardDeductionRepository.class);
        lenient().when(brackets.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(brackets.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                anyInt(), eq("married_filing_jointly"))).thenReturn(mfj2025Brackets());
        lenient().when(deductions.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", bd("15750"), bd("2000"))));
        lenient().when(deductions.findByTaxYearAndFilingStatus(anyInt(), eq("married_filing_jointly")))
                .thenReturn(Optional.of(new StandardDeductionEntity(
                        2025, "married_filing_jointly", bd("31500"), bd("1600"))));
        return new FederalTaxCalculator(brackets, deductions);
    }

    /** Age-gap stochastic household: primary born 1970 (retires 2030 at 60), spouse born 1958 (72 in
     * 2030). No income sources (keeps the surplus-tax path a no-op so the real calculator is exercised
     * ONLY through the per-year deduction tables). */
    private static GuardrailOptimizationInput ageGapStochasticInput() {
        List<ProjectionAccountInput> accounts = List.of(
                account("300000", "200000", "taxable", "joint"),
                account("150000", "150000", "traditional", "primary"),
                account("100000", "100000", "traditional", "spouse"),
                account("50000", "50000", "roth", "primary"));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1970, 90, new BigDecimal("0.03"),
                accounts, List.of(),
                new BigDecimal("40000"), BigDecimal.ZERO, new BigDecimal("0.10"),
                300, new BigDecimal("0.90"),
                List.of(new GuardrailPhaseInput("Retirement", 60, null, 1)),
                42L, BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                "married_filing_jointly", "taxable_first",
                false, null, null, 5, null, null, null, null, 2025, false, null, true,
                1958, 82, 90, new BigDecimal("0.75"), false,
                Boolean.TRUE, "male", "female", null, stepTable(82, 90));
    }


    /** A stochastic-mortality household: primary born 1962 (retires 2030 at 68), spouse born 1968.
     * SS for both spouses (primary $30k > spouse $20k) so the survivor keep-larger splice bites. When
     * {@code table} is null the run is NON-stochastic (toggle off), used for the null-guard test. */
    private static GuardrailOptimizationInput stochasticInput(Long seed, int primaryDeathAge,
                                                              int spouseDeathAge, MortalityTable table) {
        List<ProjectionAccountInput> accounts = List.of(
                account("300000", "200000", "taxable", "joint"),
                account("150000", "150000", "traditional", "primary"),
                account("100000", "100000", "traditional", "spouse"),
                account("50000", "50000", "roth", "primary"),
                account("30000", "30000", "roth", "spouse"));
        List<ProjectionIncomeSourceInput> income = List.of(
                ss("30000", "primary"), ss("20000", "spouse"));
        boolean stochastic = table != null;
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1962, 90, new BigDecimal("0.03"),
                accounts, income,
                new BigDecimal("40000"), BigDecimal.ZERO, new BigDecimal("0.10"),
                300, new BigDecimal("0.90"),
                List.of(new GuardrailPhaseInput("Retirement", 68, null, 1)),
                seed, BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                "married_filing_jointly", "taxable_first",
                false, null, null, 5, null, null, null, null, 2025, false, null, true,
                1968, primaryDeathAge, spouseDeathAge, new BigDecimal("0.75"), false,
                stochastic ? Boolean.TRUE : null, stochastic ? "male" : null,
                stochastic ? "female" : null, null, table);
    }

    private static HypotheticalAccountInput account(String balance, String basis, String type, String owner) {
        return new HypotheticalAccountInput(new BigDecimal(balance), BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.empty(), new BigDecimal(basis), type, owner);
    }

    private static ProjectionIncomeSourceInput ss(String amount, String owner) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "SS-" + owner, IncomeSourceType.SOCIAL_SECURITY, new BigDecimal(amount),
                62, null, BigDecimal.ZERO, false, "taxable",
                null, null, null, null, null, null, owner, BigDecimal.ONE);
    }

    /** Sex-keyed step table forcing a MALE to die at {@code maleDeathAge} and a FEMALE at {@code
     * femaleDeathAge}. Per the task-4 caveat, every intermediate age from 60 must be tabulated at 0.0
     * (qx returns 1.0 for any untabulated age >= the table minimum), so the death is exact. */
    private static MortalityTable stepTable(int maleDeathAge, int femaleDeathAge) {
        Map<Integer, Double> male = new HashMap<>();
        for (int age = 60; age < maleDeathAge; age++) {
            male.put(age, 0.0);
        }
        male.put(maleDeathAge, 1.0);
        Map<Integer, Double> female = new HashMap<>();
        for (int age = 60; age < femaleDeathAge; age++) {
            female.put(age, 0.0);
        }
        female.put(femaleDeathAge, 1.0);
        return new MortalityTable(male, female);
    }

    private static long countTrue(boolean[] flags) {
        long count = 0;
        for (boolean flag : flags) {
            if (flag) {
                count++;
            }
        }
        return count;
    }
}
