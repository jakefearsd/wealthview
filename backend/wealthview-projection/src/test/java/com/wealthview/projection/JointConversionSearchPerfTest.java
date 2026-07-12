package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static com.wealthview.core.testutil.TaxBracketFixtures.single2025Brackets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Manual wall-time harness for the T26 fix (JointConversionSearch's arm-scoring search honoring
 * {@code gate_on_adaptive_rules}). NOT part of the regular suite: enable with {@code -Dwv.perf=true},
 * mirroring {@link MonteCarloTaxPricingPerfTest}, e.g.
 * <pre>
 *   mvn -pl wealthview-persistence,wealthview-core,wealthview-projection test \
 *       -Dtest=JointConversionSearchPerfTest -Dwv.perf=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Unlike {@link MonteCarloTaxPricingPerfTest}'s pool-bearing fixture (which has {@code
 * optimizeConversions=false}, so the joint conversion-fraction search never runs), this fixture sets
 * {@code optimizeConversions=true} on a non-dynamic-sequencing withdrawal order specifically so {@code
 * JointConversionSearch.jointSearch} — the code path T26 changed — actually executes its ~41-candidate
 * grid-plus-refine search. Comparing {@code timing_..._gateOff} against {@code timing_..._gateOn} run
 * against a pre-T26 checkout of {@code JointConversionSearch.java} (same harness file, only that one
 * production file swapped) isolates T26's own cost contribution from T24's pre-existing
 * discretionary-search gate cost.
 */
@EnabledIfSystemProperty(named = "wv.perf", matches = "true")
class JointConversionSearchPerfTest {

    private static final int WARMUP_RUNS = 2;
    private static final int TIMED_RUNS = 5;

    private static FederalTaxCalculator realSingleCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(java.util.Optional.of(new StandardDeductionEntity(2025, "single", new BigDecimal("15000"))));
        lenient().when(taxBracketRepo.findMaxTaxYear()).thenReturn(2025);
        lenient().when(deductionRepo.findMaxTaxYear()).thenReturn(2025);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    /** Pool-bearing, conversions-enabled, non-DS fixture so {@code jointSearch} actually runs. */
    private static GuardrailOptimizationInput conversionsInput(int trialCount, boolean gateOnAdaptiveRules) {
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 92, new BigDecimal("0.03"),
                List.of(
                        new HypotheticalAccountInput(new BigDecimal("400000"), BigDecimal.ZERO, null, "taxable"),
                        new HypotheticalAccountInput(new BigDecimal("900000"), BigDecimal.ZERO, null, "traditional"),
                        new HypotheticalAccountInput(new BigDecimal("200000"), BigDecimal.ZERO, null, "roth")),
                List.of(),
                new BigDecimal("50000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), trialCount, new BigDecimal("0.90"),
                phases, 20260712L, BigDecimal.ZERO, new BigDecimal("0.10"), 0, 0, BigDecimal.ZERO,
                "single", "taxable_first", true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5, null, null,
                null, null, 2030, false, null, gateOnAdaptiveRules);
    }

    private void timeOptimize(String label, GuardrailOptimizationInput input) {
        var optimizer = new MonteCarloSpendingOptimizer(realSingleCalc(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        for (int i = 0; i < WARMUP_RUNS; i++) {
            optimizer.optimize(input);
        }

        long best = Long.MAX_VALUE;
        long total = 0;
        String gatedOn = null;
        for (int i = 0; i < TIMED_RUNS; i++) {
            long start = System.nanoTime();
            var result = optimizer.optimize(input);
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed);
            total += elapsed;
            gatedOn = result.gatedOn();
            System.out.println(label + " run " + i + ": " + (elapsed / 1_000_000) + " ms, gatedOn=" + gatedOn);
        }
        System.out.println(label + " best=" + (best / 1_000_000) + " ms, avg="
                + (total / TIMED_RUNS / 1_000_000) + " ms");

        assertThat(gatedOn).isNotNull();
    }

    @Test
    void timing_conversionsEnabled_5000Trials_gateOff() {
        timeOptimize("PERF+CONV gateOff", conversionsInput(5000, false));
    }

    /**
     * T26: same fixture, gate on. Before T26, {@code JointConversionSearch}'s arm scoring never
     * honored the toggle, so this number reflected ONLY the T24 discretionary-search gate cost, not
     * the arm-search's own. After T26, every arm-scoring {@code isSustainable} call ALSO runs the
     * reference + gated pass pair, so this number captures the fix's full cost. Compare against the
     * SAME method run on a pre-T26 checkout of {@code JointConversionSearch.java} for the
     * before/after ratio (see the T26 report for the measured numbers).
     */
    @Test
    void timing_conversionsEnabled_5000Trials_gateOn() {
        timeOptimize("PERF+CONV gateOn", conversionsInput(5000, true));
    }

    @Test
    void gateOn_actuallyGatesOnWithRules() {
        var optimizer = new MonteCarloSpendingOptimizer(realSingleCalc(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        var result = optimizer.optimize(conversionsInput(500, true));

        assertThat(result.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_WITH_RULES);
    }
}
