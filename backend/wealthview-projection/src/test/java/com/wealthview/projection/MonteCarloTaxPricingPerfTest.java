package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
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
 * Manual wall-time harness for the Monte Carlo hot loop's tax pricing (audit C5 perf guard).
 * NOT part of the regular suite: enable with {@code -Dwv.perf=true}, e.g.
 * <pre>
 *   mvn -pl wealthview-persistence,wealthview-core,wealthview-projection test \
 *       -Dtest=MonteCarloTaxPricingPerfTest -Dwv.perf=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * The fixture is deliberately pool-bearing (taxable + traditional + Roth) with REAL single-2025
 * brackets so the exact-table pricing path is actually exercised --
 * {@code MonteCarloSpendingOptimizerCharacterizationTest}'s all-taxable null-calculator fixture
 * never enters it and is useless as a timing proxy. Compare against a baseline by checking out the
 * pre-change commit in a worktree and running the same class there (the harness only touches the
 * public optimizer API, so the same file runs unmodified on both sides).
 */
@EnabledIfSystemProperty(named = "wv.perf", matches = "true")
class MonteCarloTaxPricingPerfTest {

    private static final int WARMUP_RUNS = 2;
    private static final int TIMED_RUNS = 5;

    private static FederalTaxCalculator realSingleCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        lenient().when(taxBracketRepo.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(single2025Brackets());
        lenient().when(deductionRepo.findByTaxYearAndFilingStatus(anyInt(), eq("single")))
                .thenReturn(Optional.of(new StandardDeductionEntity(2025, "single", new BigDecimal("15000"))));
        lenient().when(taxBracketRepo.findMaxTaxYear()).thenReturn(2025);
        lenient().when(deductionRepo.findMaxTaxYear()).thenReturn(2025);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    private static GuardrailOptimizationInput poolInput(int trialCount) {
        return poolInput(trialCount, null);
    }

    private static GuardrailOptimizationInput poolInput(int trialCount, BigDecimal maxAnnualAdjustmentRate) {
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
                phases, 20260712L, BigDecimal.ZERO, maxAnnualAdjustmentRate, 0, 0, BigDecimal.ZERO,
                "single", "taxable_first", false, null, null, 5, null, null,
                null, null);
    }

    @Test
    void timing_poolBearingTaxAwareOptimize_5000Trials() {
        var optimizer = new MonteCarloSpendingOptimizer(realSingleCalc(), ProjectionTestFixtures.TEST_CMA_MATRIX);
        var input = poolInput(5000);

        for (int i = 0; i < WARMUP_RUNS; i++) {
            optimizer.optimize(input);
        }

        long best = Long.MAX_VALUE;
        long total = 0;
        BigDecimal medianFinal = null;
        for (int i = 0; i < TIMED_RUNS; i++) {
            long start = System.nanoTime();
            var result = optimizer.optimize(input);
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed);
            total += elapsed;
            medianFinal = result.medianFinalBalance();
            System.out.println("PERF run " + i + ": " + (elapsed / 1_000_000) + " ms, medianFinal=" + medianFinal);
        }
        System.out.println("PERF best=" + (best / 1_000_000) + " ms, avg=" + (total / TIMED_RUNS / 1_000_000)
                + " ms");

        assertThat(medianFinal).isNotNull();
    }

    /**
     * Audit C9: same fixture but with a positive {@code max_annual_adjustment_rate}, which activates
     * the reporting-only with-rules pass (a SECOND terminal-equivalent pass over the same trials).
     * The default {@link #timing_poolBearingTaxAwareOptimize_5000Trials} fixture leaves the knob
     * null and therefore never exercises the new path, so this method exists to measure it. The
     * design budget is a &lt;2.3x total regression versus the no-rules fixture above.
     */
    @Test
    void timing_poolBearingTaxAwareOptimize_5000Trials_withGuardrailRules() {
        var optimizer = new MonteCarloSpendingOptimizer(realSingleCalc(), ProjectionTestFixtures.TEST_CMA_MATRIX);
        var input = poolInput(5000, new BigDecimal("0.10"));

        for (int i = 0; i < WARMUP_RUNS; i++) {
            optimizer.optimize(input);
        }

        long best = Long.MAX_VALUE;
        long total = 0;
        BigDecimal withRules = null;
        for (int i = 0; i < TIMED_RUNS; i++) {
            long start = System.nanoTime();
            var result = optimizer.optimize(input);
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed);
            total += elapsed;
            withRules = result.successProbabilityWithRules();
            System.out.println("PERF+RULES run " + i + ": " + (elapsed / 1_000_000) + " ms, withRules=" + withRules);
        }
        System.out.println("PERF+RULES best=" + (best / 1_000_000) + " ms, avg="
                + (total / TIMED_RUNS / 1_000_000) + " ms");

        assertThat(withRules).isNotNull();
    }
}
