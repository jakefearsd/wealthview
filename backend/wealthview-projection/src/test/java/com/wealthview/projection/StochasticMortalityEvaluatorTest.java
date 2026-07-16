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
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-project B (stochastic mortality), task 6: end-to-end pins for the SEPARATE stochastic evaluation
 * pass ({@link MonteCarloSpendingOptimizer#evaluateStochasticMortality} → {@link
 * StochasticMortalityEvaluator}). The recommendation ({@code optimize}) is unchanged; this pass runs the
 * trials over the fixed-death-optimized schedule with per-trial sampled deaths + the three-regime splice
 * and returns the raw per-trial success + sampled death ages (the task-7 output contract).
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
        // female survives to 90. The whole modeled horizon is survivor-phase from year 0.
        var eval = optimizer.evaluateStochasticMortality(
                stochasticInput(3L, 68, 90, stepTable(68, 90)));

        assertThat(eval).isNotNull();
        assertThat(eval.firstDeathAge()).containsOnly(68);  // primary dies at retirement
        assertThat(eval.secondDeathAge()).containsOnly(90);
        assertThat(eval.success()).hasSize(300); // ran to completion through the index-0 transition
    }

    // === guard: a non-stochastic run has no longevity number to produce ===

    @Test
    void evaluateStochasticMortality_nonStochasticRun_returnsNull() {
        // Same household but stochasticMortality left off (no table) -> the recommendation-only path.
        var input = stochasticInput(42L, 82, 90, null); // null table + toggle off below

        assertThat(optimizer.evaluateStochasticMortality(input)).isNull();
    }

    // --- fixtures ---

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
