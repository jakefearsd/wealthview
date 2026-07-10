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
 * {@link ProjectionTestFixtures#TEST_CMA_MATRIX} joint bootstrap (Task 15). The projection now runs
 * in REAL (today's-dollars) terms: pools grow at real matrix returns and spending is constant real,
 * so the recommendation equals each phase's target exactly ($70k/$55k/$45k, no inflation
 * escalation). The balance/percentile statistics are today's-dollars and were regenerated: a 0.2%
 * failure rate at the 90% confidence level, a positive $4.63M median terminal balance, and a
 * non-degenerate fan (10th-percentile terminal $1.88M &lt; median). These assertions are the
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
        assertThat(r.medianFinalBalance()).isEqualByComparingTo(new BigDecimal("4628581.8448"));
        assertThat(r.failureRate()).isEqualByComparingTo(new BigDecimal("0.0020"));
        assertThat(r.percentile10Final()).isEqualByComparingTo(new BigDecimal("1875947.1011"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsFirstYearSpending() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending first = optimizer.optimize(goldenInput()).yearlySpending().getFirst();

        assertThat(first.year()).isEqualTo(2030);
        assertThat(first.age()).isEqualTo(62);
        assertThat(first.phaseName()).isEqualTo("Go-Go");
        assertThat(first.recommended()).isEqualByComparingTo(new BigDecimal("70000.0000"));
        assertThat(first.corridorLow()).isEqualByComparingTo(new BigDecimal("70000.0000"));
        assertThat(first.corridorHigh()).isEqualByComparingTo(new BigDecimal("210000.0000"));
        assertThat(first.portfolioWithdrawal()).isEqualByComparingTo(new BigDecimal("70000.0000"));
        assertThat(first.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("1550000.0000"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsSlowGoPhaseYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending year = optimizer.optimize(goldenInput()).yearlySpending().get(10);

        assertThat(year.year()).isEqualTo(2040);
        assertThat(year.age()).isEqualTo(72);
        assertThat(year.phaseName()).isEqualTo("Slow-Go");
        assertThat(year.recommended()).isEqualByComparingTo(new BigDecimal("55000.0000"));
        assertThat(year.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("1947550.9909"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsNoGoPhaseYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending year = optimizer.optimize(goldenInput()).yearlySpending().get(20);

        assertThat(year.year()).isEqualTo(2050);
        assertThat(year.age()).isEqualTo(82);
        assertThat(year.phaseName()).isEqualTo("No-Go");
        assertThat(year.recommended()).isEqualByComparingTo(new BigDecimal("45000.0000"));
        assertThat(year.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("3124900.2444"));
    }

    @Test
    void optimize_seededThreePhaseScenario_pinsFinalYear() {
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending last = optimizer.optimize(goldenInput()).yearlySpending().getLast();

        assertThat(last.year()).isEqualTo(2057);
        assertThat(last.age()).isEqualTo(89);
        assertThat(last.phaseName()).isEqualTo("No-Go");
        assertThat(last.recommended()).isEqualByComparingTo(new BigDecimal("45000.0000"));
        assertThat(last.portfolioBalanceMedian()).isEqualByComparingTo(new BigDecimal("4628581.8448"));
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
