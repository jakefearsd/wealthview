package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * Pins the audit-C9 with-rules >= no-rules success monotonicity in the regime the single-taxable
 * fixtures of {@link GuardrailSimulatedRulesIntegrationTest} can never reach: a POOL-BEARING
 * portfolio (traditional-heavy, RMD-eligible ages inside the horizon, real 2025 single brackets)
 * where the spending cut interacts with the full tax machinery — exact withdrawal-tax pricing, the
 * C2 traditional gross-up, and the RMD force-out whose forced excess GROWS when the rule cuts the
 * spending draw (the candidate counterexample channel: a larger untouched traditional balance means
 * a larger forced RMD and a larger tax leak on it).
 *
 * <p>Monotonicity here is an EMPIRICAL pin, not a formal proof: floors are never cut (no
 * single-year self-inflicted shortfall is possible), but across cumulative multi-year tax paths the
 * claim rests on these pairwise pins plus a 60,000-trial-pair adversarial probe (review of the C9
 * change) that found zero floor violations and zero final-balance regressions.
 *
 * <p>Two layers:
 * <ol>
 *   <li>End-to-end: the full optimizer on a stressed pool fixture reports
 *       {@code successProbabilityWithRules >= successProbability}, for both taxable_first and
 *       traditional_first orders.
 *   <li>Pairwise per-trial: reproduce the run context via {@link OptimizationContextBuilder} with
 *       the fixture's own seed, replay {@link TrialSimulator#simulateTrial} per trial index with the
 *       adaptation off/on (same return sequences), and assert no trial flips success→failure and no
 *       trial's final balance regresses.
 * </ol>
 */
class GuardrailRulesTaxDynamicsMonotonicityTest {

    private static final long SEED = 20260712L;
    private static final int TRIALS = 1000;
    private static final double MAX_ADJ_RATE = 0.10;

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

    /**
     * Stressed pool-bearing fixture: traditional-heavy ($2.5M) so RMDs dominate from age 75 (born
     * 1968, SECURE 2.0), real single brackets, 80% confidence so a meaningful failure tail sits on
     * the margin where cuts can rescue paths.
     */
    private static GuardrailOptimizationInput poolInput(String withdrawalOrder) {
        var phases = List.of(new GuardrailPhaseInput("Retirement", 62, null, 1));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 92, new BigDecimal("0.03"),
                List.of(
                        new HypotheticalAccountInput(new BigDecimal("300000"), BigDecimal.ZERO,
                                null, "taxable"),
                        new HypotheticalAccountInput(new BigDecimal("2500000"), BigDecimal.ZERO,
                                null, "traditional"),
                        new HypotheticalAccountInput(new BigDecimal("200000"), BigDecimal.ZERO,
                                null, "roth")),
                List.of(),
                new BigDecimal("60000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), TRIALS, new BigDecimal("0.80"),
                phases, SEED, BigDecimal.ZERO, new BigDecimal("0.10"), 0, 0, BigDecimal.ZERO,
                "single", withdrawalOrder, false, null, null, 5, null, null,
                null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"taxable_first", "traditional_first"})
    void optimize_poolBearingRmdEligibleFixture_withRulesMeetsOrExceedsNoRules(String order) {
        var optimizer = new MonteCarloSpendingOptimizer(realSingleCalc(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        var r = optimizer.optimize(poolInput(order));

        // Pinned: the optimizer calibrates to the 0.80 gate; the simulated rule rescues the entire
        // failure tail of this fixture, under full RMD/withdrawal-tax/gross-up dynamics.
        assertThat(r.successProbability()).isEqualByComparingTo(new BigDecimal("0.8000"));
        assertThat(r.successProbabilityWithRules()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(r.successProbabilityWithRules()).isGreaterThanOrEqualTo(r.successProbability());
    }

    @ParameterizedTest
    @ValueSource(strings = {"taxable_first", "traditional_first"})
    void simulateTrial_pairwiseUnderRmdAndTaxDynamics_noSuccessFlipOrBalanceRegression(String order) {
        var contextBuilder = new OptimizationContextBuilder(realSingleCalc(), null);
        var ctx = contextBuilder.build(poolInput(order), ProjectionTestFixtures.TEST_CMA_MATRIX);
        int years = ctx.sim().years();
        int trials = ctx.sim().trialCount();

        // Mechanism-engagement guards: the tax machinery this test exists to exercise must be live.
        assertThat(ctx.taxIncome().ordinaryTaxTableByYear()).isNotNull();       // exact withdrawal tax
        assertThat(ctx.portfolio().initTraditional()).isGreaterThan(0);         // pools in play
        assertThat(ctx.sim().rmdStartAge())                                     // RMD years in horizon
                .isLessThan(ctx.sim().retirementAge() + years);

        double[] floors = ctx.taxIncome().adjustedFloors();
        double[] discretionary = new double[years];
        Arrays.fill(discretionary, 30_000.0);

        var simulator = new TrialSimulator();

        // Pass 1 (no rules): collect per-trial results and per-year balances for the median
        // reference, exactly like GuardrailResponseBuilder's headline terminal pass.
        var noRules = new TrialSimulator.TrialResult[trials];
        double[][] yearBalances = new double[years][trials];
        for (int t = 0; t < trials; t++) {
            noRules[t] = simulator.simulateTrial(
                    ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                    floors, discretionary, years, config(ctx, t, null));
            for (int y = 0; y < years; y++) {
                yearBalances[y][t] = noRules[t].yearBalances()[y];
            }
        }

        // Corridor + expected-start-balance reference, mirroring the production builder.
        double[][] corridors = SpendingCorridorCalculator.computeCorridors(
                ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(),
                floors, discretionary, years, trials);
        SpendingCorridorCalculator.smoothCorridors(corridors[0], corridors[1], years);
        double[] expectedStart = new double[years];
        expectedStart[0] = ctx.portfolio().initTaxable() + ctx.portfolio().initTraditional()
                + ctx.portfolio().initRoth();
        for (int y = 1; y < years; y++) {
            double[] sorted = yearBalances[y - 1].clone();
            Arrays.sort(sorted);
            expectedStart[y] = PercentileCalculator.percentile(sorted, 0.50);
        }
        var adaptation = new TrialSimulator.GuardrailAdaptation(
                corridors[0], corridors[1], expectedStart, MAX_ADJ_RATE);

        // Pass 2 (with rules): same trial indices, same return sequences — pairwise comparison.
        int successWithout = 0;
        int successWith = 0;
        int strictlyImproved = 0;
        for (int t = 0; t < trials; t++) {
            var with = simulator.simulateTrial(
                    ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                    floors, discretionary, years, config(ctx, t, adaptation));

            // No trial may flip success -> failure under the rule.
            if (noRules[t].success()) {
                assertThat(with.success())
                        .as("trial %d flipped success->failure under the adaptation rule", t)
                        .isTrue();
            }
            // No trial's final balance may regress (cuts only reduce withdrawals).
            assertThat(with.finalBalance())
                    .as("trial %d final balance regressed under the adaptation rule", t)
                    .isGreaterThanOrEqualTo(noRules[t].finalBalance() - 0.01);

            successWithout += noRules[t].success() ? 1 : 0;
            successWith += with.success() ? 1 : 0;
            if (with.finalBalance() > noRules[t].finalBalance() + 1.0) {
                strictlyImproved++;
            }
        }

        // Aggregate monotonicity + non-vacuity: the rule must actually have engaged (cut) on a
        // meaningful share of paths, otherwise this pairwise pin proves nothing.
        assertThat(successWith).isGreaterThanOrEqualTo(successWithout);
        assertThat(strictlyImproved).isGreaterThan(0);
    }

    /** Mirrors {@code GuardrailResponseBuilder.buildSimConfig} for the pool-bearing case. */
    private static TrialSimulator.SimulationConfig config(OptimizationSetup ctx, int t,
                                                          TrialSimulator.GuardrailAdaptation adaptation) {
        return TrialSimulator.SimulationConfig.builder(
                        ctx.portfolio().initTaxable(), ctx.portfolio().initTraditional(),
                        ctx.portfolio().initRoth(), ctx.portfolio().withdrawalOrder())
                .taxTables(ctx.taxIncome().ordinaryTaxTableByYear(), ctx.taxIncome().rentalAwareTaxableIncome())
                .retirementAge(ctx.sim().retirementAge())
                .dsBracketCeilingByYear(ctx.taxIncome().dsBracketCeilingByYear())
                .cashReserve(ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate())
                .trackYearBalances(true)
                .returns(ctx.sim().taxableReturns()[t], ctx.sim().traditionalReturns()[t],
                        ctx.sim().rothReturns()[t])
                .rmdStartAge(ctx.sim().rmdStartAge())
                .taxableBasis(ctx.portfolio().initTaxableBasis())
                .ltcgTaxTableByYear(ctx.taxIncome().ltcgTaxTableByYear())
                .dividendYield(ctx.sim().dividendYield())
                .adaptation(adaptation)
                .build();
    }
}
