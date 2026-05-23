package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-master characterization test for {@link MonteCarloSpendingOptimizer}.
 *
 * <p>Monte Carlo output is reproducible because {@link GuardrailOptimizationInput} carries
 * a {@code seed}; the optimizer constructs every {@code Random} from {@code input.seed()}
 * (see {@code MonteCarloSpendingOptimizer} lines 188 / 341 / 526), so a fixed seed pins
 * the entire simulation. No production seam was needed — the seed hook already exists.
 *
 * <p>The fixture is a healthy three-phase retirement (target spending well within the
 * portfolio's capacity): an 8.4% failure rate at the 90% confidence level and a positive,
 * plausible $9.46M median terminal balance. Every value below was produced by the optimizer
 * and sanity-checked. These assertions are the behavior contract the Phase 3 decomposition
 * of this God-class must preserve.
 */
class MonteCarloSpendingOptimizerCharacterizationTest {

    private static final long SEED = 20260516L;

    /** Three-phase retirement with explicit per-phase target spending, fixed MC seed. */
    private GuardrailOptimizationInput goldenInput() {
        var phases = List.of(
                new GuardrailPhaseInput("Go-Go", 62, 71, 1, new BigDecimal("70000")),
                new GuardrailPhaseInput("Slow-Go", 72, 81, 1, new BigDecimal("55000")),
                new GuardrailPhaseInput("No-Go", 82, null, 1, new BigDecimal("45000")));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 90, new BigDecimal("0.03"),
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("1500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                List.of(), new BigDecimal("40000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), 1000, new BigDecimal("0.90"),
                phases, SEED, BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                null, null, false, null, null, 5, null, null);
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsAggregateStatistics() {
        var optimizer = new MonteCarloSpendingOptimizer(null);

        GuardrailProfileResponse r = optimizer.optimize(goldenInput());

        assertThat(r.yearlySpending()).hasSize(28);
        assertThat(r.medianFinalBalance()).isEqualByComparingTo(new BigDecimal("9461468.4563"));
        assertThat(r.failureRate()).isEqualByComparingTo(new BigDecimal("0.0840"));
        assertThat(r.percentile10Final()).isEqualByComparingTo(new BigDecimal("405515.1143"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsFirstYearSpending() {
        var optimizer = new MonteCarloSpendingOptimizer(null);

        GuardrailYearlySpending first = optimizer.optimize(goldenInput()).yearlySpending().getFirst();

        assertThat(first.year()).isEqualTo(2030);
        assertThat(first.age()).isEqualTo(62);
        assertThat(first.phaseName()).isEqualTo("Go-Go");
        assertThat(first.recommended()).isEqualByComparingTo(new BigDecimal("74391.6379"));
        assertThat(first.corridorLow()).isEqualByComparingTo(new BigDecimal("74391.6379"));
        assertThat(first.corridorHigh()).isEqualByComparingTo(new BigDecimal("223174.9138"));
        assertThat(first.portfolioWithdrawal()).isEqualByComparingTo(new BigDecimal("74391.6379"));
        assertThat(first.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("1582157.3621"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsSlowGoPhaseYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null);

        GuardrailYearlySpending year = optimizer.optimize(goldenInput()).yearlySpending().get(10);

        assertThat(year.year()).isEqualTo(2040);
        assertThat(year.age()).isEqualTo(72);
        assertThat(year.phaseName()).isEqualTo("Slow-Go");
        assertThat(year.recommended()).isEqualByComparingTo(new BigDecimal("76866.3979"));
        assertThat(year.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("2738225.8049"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsNoGoPhaseYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null);

        GuardrailYearlySpending year = optimizer.optimize(goldenInput()).yearlySpending().get(20);

        assertThat(year.year()).isEqualTo(2050);
        assertThat(year.age()).isEqualTo(82);
        assertThat(year.phaseName()).isEqualTo("No-Go");
        assertThat(year.recommended()).isEqualByComparingTo(new BigDecimal("82282.2919"));
        assertThat(year.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("5147287.9565"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsFinalYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null);

        GuardrailYearlySpending last = optimizer.optimize(goldenInput()).yearlySpending().getLast();

        assertThat(last.year()).isEqualTo(2057);
        assertThat(last.age()).isEqualTo(89);
        assertThat(last.phaseName()).isEqualTo("No-Go");
        assertThat(last.recommended()).isEqualByComparingTo(new BigDecimal("98889.4027"));
        assertThat(last.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("9461468.4563"));
    }

    @Test
    void optimize_sameSeed_producesIdenticalOutputAcrossRuns() {
        // Determinism guarantee: the fixed seed makes the whole Monte Carlo run
        // reproducible, which is what makes this characterization test a valid
        // refactor gate.
        var optimizer = new MonteCarloSpendingOptimizer(null);

        GuardrailProfileResponse a = optimizer.optimize(goldenInput());
        GuardrailProfileResponse b = optimizer.optimize(goldenInput());

        assertThat(a.medianFinalBalance()).isEqualByComparingTo(b.medianFinalBalance());
        assertThat(a.failureRate()).isEqualByComparingTo(b.failureRate());
        assertThat(a.percentile10Final()).isEqualByComparingTo(b.percentile10Final());
        for (int i = 0; i < a.yearlySpending().size(); i++) {
            assertThat(a.yearlySpending().get(i).recommended())
                    .isEqualByComparingTo(b.yearlySpending().get(i).recommended());
        }
    }

    @Test
    void optimize_sameSeed_everyYearlyFieldByteIdenticalAcrossRuns() {
        // Stronger reproducibility gate than the recommended-only check above: asserts
        // EVERY field of EVERY yearly entry is byte-identical across two seeded runs. This
        // is the end-to-end determinism safety-net that makes parallelizing the trial loop
        // safe — parallel execution must not perturb any output value.
        var optimizer = new MonteCarloSpendingOptimizer(null);

        GuardrailProfileResponse a = optimizer.optimize(goldenInput());
        GuardrailProfileResponse b = optimizer.optimize(goldenInput());

        assertThat(a.medianFinalBalance()).isEqualByComparingTo(b.medianFinalBalance());
        assertThat(a.failureRate()).isEqualByComparingTo(b.failureRate());
        assertThat(a.percentile10Final()).isEqualByComparingTo(b.percentile10Final());
        assertThat(a.yearlySpending()).hasSameSizeAs(b.yearlySpending());
        for (int i = 0; i < a.yearlySpending().size(); i++) {
            GuardrailYearlySpending ya = a.yearlySpending().get(i);
            GuardrailYearlySpending yb = b.yearlySpending().get(i);
            assertThat(ya.year()).isEqualTo(yb.year());
            assertThat(ya.age()).isEqualTo(yb.age());
            assertThat(ya.phaseName()).isEqualTo(yb.phaseName());
            assertThat(ya.recommended()).isEqualByComparingTo(yb.recommended());
            assertThat(ya.corridorLow()).isEqualByComparingTo(yb.corridorLow());
            assertThat(ya.corridorHigh()).isEqualByComparingTo(yb.corridorHigh());
            assertThat(ya.portfolioWithdrawal()).isEqualByComparingTo(yb.portfolioWithdrawal());
            assertThat(ya.portfolioBalanceMedian()).isEqualByComparingTo(yb.portfolioBalanceMedian());
        }
    }
}
