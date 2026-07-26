package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T24: end-to-end tests for the {@code gate_on_adaptive_rules} toggle -- when set, the
 * sustainability search's candidate evaluation runs WITH the audit-C9 simulated guardrail-adaptation
 * rule active (see {@link SustainabilitySearch#isSustainable}) and gates on {@code
 * success_probability_with_rules} instead of {@code success_probability}. {@code
 * success_probability}/{@code success_probability_with_rules} themselves keep their STABLE meaning
 * (no-adaptation / with-rules rate on the FINAL schedule) regardless of the toggle -- only which one
 * certified the recommended spending level changes.
 *
 * <p>Reuses the same stressed all-taxable fixture as {@code GuardrailSimulatedRulesIntegrationTest}
 * (calibrated right to the 80%-confidence edge, so adaptation has a meaningful failure tail to
 * rescue) so the untoggled numbers here are directly comparable to that class's pinned reporting-only
 * with-rules rate.
 */
class GuardrailAdaptiveGateIntegrationTest {

    private static final long SEED = 20260712L;

    private GuardrailOptimizationInput stressedInput(boolean gateOnAdaptiveRules) {
        var phases = List.of(new GuardrailPhaseInput("Retirement", 62, null, 1));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 92, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("700000"), BigDecimal.ZERO, null, "taxable")),
                List.of(), new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), 2000, new BigDecimal("0.80"),
                phases, SEED, BigDecimal.ZERO, new BigDecimal("0.10"), 0, 0, BigDecimal.ZERO,
                null, null, false, null, null, 5, null, null,
                null, null, 2030, false, null, gateOnAdaptiveRules,
                null, null, null, null, false, null, null, null, null, null);   // household task 6: single-person
    }

    @Test
    void optimize_toggleOff_gatesOnNoAdaptationAndReportsBothRates() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse r = optimizer.optimize(stressedInput(false));

        // Pinned: identical to GuardrailSimulatedRulesIntegrationTest's stressedInput() numbers --
        // this fixture is byte-for-byte the same, gate off is the pre-T24 (and default) behavior.
        assertThat(r.successProbability()).isEqualByComparingTo(new BigDecimal("0.8000"));
        assertThat(r.successProbabilityWithRules()).isEqualByComparingTo(new BigDecimal("0.9785"));
        assertThat(r.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_NO_ADAPTATION);
    }

    @Test
    void optimize_toggleOn_recommendsStrictlyMoreSpendingThanToggleOff() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse off = optimizer.optimize(stressedInput(false));
        GuardrailProfileResponse on = optimizer.optimize(stressedInput(true));

        BigDecimal recommendedOff = off.yearlySpending().getFirst().recommended();
        BigDecimal recommendedOn = on.yearlySpending().getFirst().recommended();

        // Monotonicity (design requirement): gating on the with-rules metric only widens what the
        // search accepts as sustainable (adaptation rescues paths, never costs them), so the
        // recommended level can only rise relative to the no-adaptation gate, for the same inputs.
        assertThat(recommendedOn).isGreaterThan(recommendedOff);
        // Pinned exact values (seeded, deterministic).
        assertThat(recommendedOff).isEqualByComparingTo(new BigDecimal("45093.2574"));
        assertThat(recommendedOn).isEqualByComparingTo(new BigDecimal("52407.6111"));
    }

    @Test
    void optimize_toggleOn_gatesOnWithRulesMeetingConfidenceWhileNoAdaptationRateMayLag() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse r = optimizer.optimize(stressedInput(true));

        // The search calibrated candidates to the 0.80 confidence target using the WITH-RULES rate,
        // so that is the metric that lands at (or above) the target on the final schedule ...
        assertThat(r.successProbabilityWithRules()).isGreaterThanOrEqualTo(new BigDecimal("0.80"));
        // ... while the plain no-adaptation rate -- now measuring a HIGHER discretionary spending
        // level than the toggle-off run calibrated to -- may fall below the target. This is the
        // semantic the user chose: success_probability/success_probability_with_rules keep their
        // stable, always-both-computed meaning; only which one gated the search changes.
        assertThat(r.successProbability()).isLessThan(new BigDecimal("0.80"));
        assertThat(r.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_WITH_RULES);
        // Pinned exact values (seeded, deterministic).
        assertThat(r.successProbability()).isEqualByComparingTo(new BigDecimal("0.5435"));
        assertThat(r.successProbabilityWithRules()).isEqualByComparingTo(new BigDecimal("0.8000"));
    }

    @Test
    void optimize_toggleOnSameSeed_reproducesIdenticalResults() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse a = optimizer.optimize(stressedInput(true));
        GuardrailProfileResponse b = optimizer.optimize(stressedInput(true));

        assertThat(a.successProbability()).isEqualByComparingTo(b.successProbability());
        assertThat(a.successProbabilityWithRules()).isEqualByComparingTo(b.successProbabilityWithRules());
        assertThat(a.yearlySpending().getFirst().recommended())
                .isEqualByComparingTo(b.yearlySpending().getFirst().recommended());
        assertThat(a.gatedOn()).isEqualTo(b.gatedOn());
    }

    /**
     * Permanent bisection-soundness sweep (T24 review request): {@code SustainabilitySearch}'s
     * binary searches assume {@code isSustainable} is MONOTONE in the uniform discretionary level.
     * Under the adaptive-rules gate the rule inputs are re-derived from each candidate schedule, so
     * that monotonicity is not formally proven -- this sweep empirically pins it on the stressed
     * fixture: a 1000-unit coarse grid over candidate discretionary levels, then a 100-unit
     * refinement through the transition window, asserting EXACTLY ONE sustainable-to-unsustainable
     * transition (a re-entrant "sustainable again above an unsustainable level" pocket would break
     * bisection). Mirrors the empirically-pinned-not-proven stance documented on
     * {@code TrialSimulator#adaptYearSpending} and
     * {@code SustainabilitySearch#binarySearchDiscretionary}.
     */
    @Test
    void isSustainable_adaptiveGateCandidateGrid_exactlyOneSustainabilityTransition() {
        var contextBuilder = new OptimizationContextBuilder(null, null);
        var ctx = contextBuilder.build(stressedInput(true), ProjectionTestFixtures.TEST_CMA_MATRIX);
        int years = ctx.sim().years();
        var returnPaths = new PortfolioReturnPaths(ctx.sim().taxableReturns(), ctx.sim().traditionalReturns(),
                ctx.sim().rothReturns(), ctx.sim().portfolioPaths());
        var searchContext = new SustainabilitySearch.SearchContext(
                ctx, returnPaths, ctx.sim().trialCount(), ctx.taxIncome().taxCtx(), null, null,
                true, 0.10);
        var search = new SustainabilitySearch(new TrialSimulator());
        double[] floors = ctx.taxIncome().adjustedFloors();

        // Coarse 1000-unit grid. The toggled-on recommendation sits near 22.4k discretionary
        // (52407 recommended - 30000 floor), so 0..40000 safely brackets the transition.
        double coarseStep = 1000;
        int coarsePoints = 41;
        double lastSustainable = -1;
        double firstUnsustainable = -1;
        int coarseTransitions = 0;
        boolean prev = evaluateAt(search, searchContext, floors, years, 0);
        assertThat(prev).as("grid start (discretionary=0) must be sustainable").isTrue();
        for (int i = 1; i < coarsePoints; i++) {
            double level = i * coarseStep;
            boolean sustainable = evaluateAt(search, searchContext, floors, years, level);
            if (sustainable != prev) {
                coarseTransitions++;
                assertThat(prev)
                        .as("re-entrant transition at %s: unsustainable->sustainable breaks bisection", level)
                        .isTrue();
                lastSustainable = level - coarseStep;
                firstUnsustainable = level;
            }
            prev = sustainable;
        }
        assertThat(coarseTransitions).as("exactly one coarse sustainable->unsustainable transition").isEqualTo(1);
        assertThat(prev).as("grid end (discretionary=40000) must be unsustainable").isFalse();

        // 100-unit refinement through the coarse transition window: same single-transition law.
        int fineTransitions = 0;
        boolean prevFine = true;   // window start == lastSustainable, known sustainable
        for (double level = lastSustainable + 100; level <= firstUnsustainable + 1e-9; level += 100) {
            boolean sustainable = evaluateAt(search, searchContext, floors, years, level);
            if (sustainable != prevFine) {
                fineTransitions++;
                assertThat(prevFine)
                        .as("re-entrant fine transition at %s", level)
                        .isTrue();
            }
            prevFine = sustainable;
        }
        assertThat(fineTransitions).as("exactly one fine sustainable->unsustainable transition").isEqualTo(1);
        assertThat(prevFine).as("refinement end must be unsustainable").isFalse();
    }

    private static boolean evaluateAt(SustainabilitySearch search,
                                       SustainabilitySearch.SearchContext searchContext,
                                       double[] floors, int years, double discretionaryLevel) {
        double[] discretionary = new double[years];
        Arrays.fill(discretionary, discretionaryLevel);
        return search.isSustainable(searchContext, floors, discretionary);
    }

    @Test
    void optimize_toggleOnButNoAdjustmentRate_fallsBackToNoAdaptationGate() {
        // No positive max_annual_adjustment_rate means the rule cannot move spending -- the search
        // silently falls back to the no-adaptation gate even with the toggle on, and gated_on
        // reflects that accurately (it describes what ACTUALLY certified the run, not raw intent).
        var phases = List.of(new GuardrailPhaseInput("Retirement", 62, null, 1));
        var noRateInput = new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 92, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("700000"), BigDecimal.ZERO, null, "taxable")),
                List.of(), new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), 2000, new BigDecimal("0.80"),
                phases, SEED, BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                null, null, false, null, null, 5, null, null,
                null, null, 2030, false, null, true,
                null, null, null, null, false, null, null, null, null, null);   // household task 6: single-person
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse r = optimizer.optimize(noRateInput);

        assertThat(r.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_NO_ADAPTATION);
        assertThat(r.successProbability()).isEqualByComparingTo(new BigDecimal("0.8000"));
        assertThat(r.successProbabilityWithRules()).isNull();
    }
}
