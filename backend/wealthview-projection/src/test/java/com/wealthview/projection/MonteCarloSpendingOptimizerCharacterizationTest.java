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
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-master characterization test for {@link MonteCarloSpendingOptimizer}.
 *
 * <p>Monte Carlo output is reproducible because {@link GuardrailOptimizationInput} carries
 * a {@code seed}; the optimizer constructs every {@code Random} from {@code input.seed()}
 * (see {@code MonteCarloSpendingOptimizer} lines 188 / 341 / 526), so a fixed seed pins
 * the entire simulation. No production seam was needed — the seed hook already exists.
 *
 * <p>The fixture is a healthy three-phase retirement: a $1.5M all-US-equity (allocation-driven)
 * taxable account whose Monte Carlo returns are drawn per-pool from the shared
 * {@link ProjectionTestFixtures#TEST_CMA_MATRIX} joint bootstrap (Task 15). Spending stays capped
 * at the per-phase targets (well within capacity), so the recommendation values are unchanged; the
 * balance/percentile statistics were regenerated against the new return model and sanity-checked:
 * a 0.1% failure rate at the 90% confidence level, a positive $10.84M median terminal balance, and
 * a non-degenerate fan (10th-percentile terminal $4.49M &lt; median). These assertions are the
 * behavior contract the optimizer must preserve.
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
                        null, "taxable")),
                List.of(), new BigDecimal("40000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), 1000, new BigDecimal("0.90"),
                phases, SEED, BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                null, null, false, null, null, 5, null, null);
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsAggregateStatistics() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse r = optimizer.optimize(goldenInput());

        assertThat(r.yearlySpending()).hasSize(28);
        assertThat(r.medianFinalBalance()).isEqualByComparingTo(new BigDecimal("10842861.2838"));
        assertThat(r.failureRate()).isEqualByComparingTo(new BigDecimal("0.0010"));
        assertThat(r.percentile10Final()).isEqualByComparingTo(new BigDecimal("4485983.3902"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsFirstYearSpending() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending first = optimizer.optimize(goldenInput()).yearlySpending().getFirst();

        assertThat(first.year()).isEqualTo(2030);
        assertThat(first.age()).isEqualTo(62);
        assertThat(first.phaseName()).isEqualTo("Go-Go");
        assertThat(first.recommended()).isEqualByComparingTo(new BigDecimal("74391.6379"));
        assertThat(first.corridorLow()).isEqualByComparingTo(new BigDecimal("74391.6379"));
        assertThat(first.corridorHigh()).isEqualByComparingTo(new BigDecimal("223174.9138"));
        assertThat(first.portfolioWithdrawal()).isEqualByComparingTo(new BigDecimal("74391.6379"));
        assertThat(first.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("1594208.3621"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsSlowGoPhaseYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending year = optimizer.optimize(goldenInput()).yearlySpending().get(10);

        assertThat(year.year()).isEqualTo(2040);
        assertThat(year.age()).isEqualTo(72);
        assertThat(year.phaseName()).isEqualTo("Slow-Go");
        assertThat(year.recommended()).isEqualByComparingTo(new BigDecimal("76866.3979"));
        assertThat(year.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("2725836.8832"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsNoGoPhaseYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending year = optimizer.optimize(goldenInput()).yearlySpending().get(20);

        assertThat(year.year()).isEqualTo(2050);
        assertThat(year.age()).isEqualTo(82);
        assertThat(year.phaseName()).isEqualTo("No-Go");
        assertThat(year.recommended()).isEqualByComparingTo(new BigDecimal("82282.2919"));
        assertThat(year.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("5924788.6681"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsFinalYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending last = optimizer.optimize(goldenInput()).yearlySpending().getLast();

        assertThat(last.year()).isEqualTo(2057);
        assertThat(last.age()).isEqualTo(89);
        assertThat(last.phaseName()).isEqualTo("No-Go");
        assertThat(last.recommended()).isEqualByComparingTo(new BigDecimal("98889.4027"));
        assertThat(last.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("10842861.2838"));
    }

    @Test
    void optimize_sameSeed_producesIdenticalOutputAcrossRuns() {
        // Determinism guarantee: the fixed seed makes the whole Monte Carlo run
        // reproducible, which is what makes this characterization test a valid
        // refactor gate.
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

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
}
