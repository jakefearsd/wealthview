package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.mortality.MortalityTable;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import com.wealthview.projection.testutil.ProjectionTestFixtures;
import com.wealthview.projection.testutil.SsaMortalityTables;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Sub-project B (stochastic mortality), task 10: the seed-pinned regression "golden" for the
 * stochastic-mortality output.
 *
 * <p><b>Why a dedicated regression test, not the {@link ProjectionGoldenFileTest} @ValueSource.</b>
 * The six existing goldens pin the DETERMINISTIC year-by-year engine ({@code
 * DeterministicProjectionEngine.run(ProjectionInput)}). The stochastic-mortality summary is produced
 * by a completely different path — the SEEDED Monte Carlo / guardrail optimizer ({@code
 * MonteCarloSpendingOptimizer.optimizeInternal(GuardrailOptimizationInput)} → {@link
 * StochasticMortalitySummary}) — with a different input type and a different output object. The
 * deterministic golden harness cannot capture it. So this task pins the SAME intent (a reproducible,
 * hand-verified regression anchor) with the CORRECT harness: a fixed-seed run of the MC optimizer
 * whose {@link StochasticMortalitySummary} scalars are pinned below. The A goldens remain the
 * byte-identical toggle-off anchor (see {@code ProjectionGoldenFileTest}); nothing here re-pins them.
 *
 * <p><b>Fixture.</b> The sub-project A {@code household-survivor} couple — primary born 1958 (male),
 * spouse born 1966 (female), retirement 2030, horizon 2066 (primary end age 108) — run with
 * {@code stochasticMortality = true}, {@code longevityConditionalAge = 95}, {@code trialCount = 2000}
 * (stable percentiles), a FIXED seed, and the REAL SSA period-life table parsed from the production
 * Flyway seed ({@link SsaMortalityTables}). The essential floor is raised to $180k so the portfolio
 * is genuinely stressed — otherwise the guaranteed SS+pension income ($90k) trivially covers spending
 * and every trial succeeds, making the pin (and the sanity relationships) meaningless.
 *
 * <p><b>Hand-verification (against the SSA qx table + the fixed-death A run).</b>
 * <ul>
 *   <li>{@code lifetimeSuccessProbability} = 0.9315 (1863/2000) ≥ the A fixed-death (85/90) success
 *       0.909 (1818/2000): stochastic trials that die BEFORE the fixed 85/90 horizon fund a shorter
 *       plan, so early death raises success — the brief's first sanity check, satisfied with a
 *       +45-trial margin. (At a much higher floor the longevity tail — survivors to 97+ — flips this;
 *       $180k sits comfortably in the early-death-dominated regime.)</li>
 *   <li>{@code secondDeathAge} median 88 sits ~3 years above the female-alone median death age of 85
 *       (SSA female alive at 64 → mean LE 20.0y / median death 85), exactly as expected since the
 *       survivor is the MAX of the two sampled ages — the brief's "near female cohort life
 *       expectancy" check.</li>
 *   <li>{@code longevityConditional.probability} 0.8427 (300/356) ≤ lifetime 0.9315: conditioning on
 *       the survivor reaching 95+ selects the longest-lived, hardest-to-fund trials — the brief's
 *       third sanity check.</li>
 *   <li>{@code longevityConditional.trialFraction} 0.178 (356/2000) matches the analytic
 *       P(at least one spouse alive at 95) = 1 − (1−0.1192)(1−0.0696) = 0.1805 (independent draws:
 *       female-64→95 survival 0.1192, male-72→95 survival 0.0696) to within MC sampling error,
 *       confirming the sampler draws + spouse independence + the subset logic.</li>
 *   <li>{@code firstDeathAge} (own-age of whichever spouse dies first — {@code MortalityDrawGenerator}
 *       maps it to {@code primarySurvives ? spouseDeathAge : primaryDeathAge}) has a distribution FLOOR
 *       of 64, the FEMALE spouse's retirement-age sampling floor (2030 − 1966), since the younger
 *       spouse can be the first to die at her own age 64; ~9% of trials fall below 72. The pinned
 *       p10 of 72 is therefore a boundary of the male/female mixture, NOT a hard floor. median 80,
 *       p90 90.</li>
 * </ul>
 */
class StochasticMortalityGoldenTest {

    private static final long SEED = 20260716L;
    private static final int TRIALS = 2000;
    /** $180k stresses the $2.1M portfolio against $90k guaranteed income (see class javadoc). */
    private static final String ESSENTIAL_FLOOR = "180000";
    private static final MortalityTable SSA_TABLE = SsaMortalityTables.load();

    private final MonteCarloSpendingOptimizer optimizer =
            new MonteCarloSpendingOptimizer(realMfjSingleCalc(), ProjectionTestFixtures.TEST_CMA_MATRIX);

    @Test
    void optimizeInternal_householdStochasticMortality_matchesSeedPinnedGolden() {
        var summary = optimizer.optimizeInternal(household(SSA_TABLE)).stochasticMortality();

        assertThat(summary).isNotNull();
        // Lifetime (unconditional) success: 1863 of 2000 trials.
        assertThat(summary.lifetimeSuccessProbability()).isCloseTo(0.9315, within(1e-9));
        // Longevity-conditional (survivor reaches 95): 300 of 356 qualifying trials succeed.
        assertThat(summary.longevityConditional().age()).isEqualTo(95);
        assertThat(summary.longevityConditional().probability()).isCloseTo(0.8426966292134831, within(1e-9));
        assertThat(summary.longevityConditional().trialFraction()).isCloseTo(0.178, within(1e-9));
        // Raw sampled death-age distributions (whole years).
        assertThat(summary.firstDeathAge())
                .isEqualTo(new StochasticMortalitySummary.AgeDistribution(72, 80, 90));
        assertThat(summary.secondDeathAge())
                .isEqualTo(new StochasticMortalitySummary.AgeDistribution(78, 88, 97));
    }

    @Test
    void optimizeInternal_householdStochasticMortality_summaryReconcilesWithMechanics() {
        double fixedDeathSuccess = optimizer.optimize(household(null)).successProbability().doubleValue();
        var summary = optimizer.optimizeInternal(household(SSA_TABLE)).stochasticMortality();

        // Sub-project A fixed-death (85/90) headline success is the toggle-off anchor: 1818/2000.
        assertThat(fixedDeathSuccess).isCloseTo(0.909, within(1e-9));
        // Early-death trials help -> stochastic lifetime success is at least the fixed-death rate.
        assertThat(summary.lifetimeSuccessProbability()).isGreaterThanOrEqualTo(fixedDeathSuccess);
        // Conditioning on a long-lived survivor is strictly harder than the unconditional rate.
        assertThat(summary.longevityConditional().probability())
                .isLessThan(summary.lifetimeSuccessProbability());
        // Survivor (second) death age brackets the female cohort life expectancy (median death ~85).
        assertThat(summary.secondDeathAge().median()).isBetween(84, 92);
    }

    // === fixtures ===

    /** The sub-project A {@code household-survivor} couple (births 1958/1966, retirement 2030,
     * horizon 2066) as a Monte Carlo optimization input. {@code table} null ⇒ toggle OFF (sub-project
     * A fixed-death path, primary/spouse death ages 85/90); non-null ⇒ stochastic mortality with
     * primary=male, spouse=female, longevity-conditional age 95. */
    private GuardrailOptimizationInput household(MortalityTable table) {
        List<ProjectionAccountInput> accounts = List.of(
                account("800000", "500000", "taxable", "joint"),
                account("900000", "900000", "traditional", "primary"),
                account("400000", "400000", "traditional", "spouse"));
        List<ProjectionIncomeSourceInput> income = List.of(
                income("Primary Social Security", IncomeSourceType.SOCIAL_SECURITY, "38000", "primary", "1"),
                income("Spouse Social Security", IncomeSourceType.SOCIAL_SECURITY, "22000", "spouse", "1"),
                income("Single-Life-Election Pension", IncomeSourceType.PENSION, "30000", "primary", "0.5"));
        boolean stochastic = table != null;
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1958, 108, new BigDecimal("0.025"),
                accounts, income,
                new BigDecimal(ESSENTIAL_FLOOR), BigDecimal.ZERO, null,
                TRIALS, new BigDecimal("0.90"),
                List.of(new GuardrailPhaseInput("Retirement", 72, null, 1)),
                SEED, BigDecimal.ZERO, null, 0, 0, BigDecimal.ZERO,
                "married_filing_jointly", "taxable_first",
                false, null, null, 5, null, null, null, null, 2025, false, null, true,
                1966, 85, 90, new BigDecimal("0.75"), false,
                stochastic ? Boolean.TRUE : null, stochastic ? "male" : null,
                stochastic ? "female" : null, 95, table);
    }

    private static HypotheticalAccountInput account(String balance, String basis, String type, String owner) {
        return new HypotheticalAccountInput(new BigDecimal(balance), BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.empty(), new BigDecimal(basis), type, owner);
    }

    private static ProjectionIncomeSourceInput income(String name, IncomeSourceType type, String amount,
                                                      String owner, String survivorPercent) {
        return new ProjectionIncomeSourceInput(
                UUID.nameUUIDFromBytes((name + owner).getBytes()), name, type, new BigDecimal(amount),
                62, null, new BigDecimal("0.025"), false, "taxable",
                null, null, null, null, null, null, owner, new BigDecimal(survivorPercent));
    }

    /** A real {@link FederalTaxCalculator} over the shared 2025 MFJ+single fixtures so the survivor's
     * MFJ-to-single filing flip at first death is economically live in the trial simulation. */
    private static FederalTaxCalculator realMfjSingleCalc() {
        var brackets = Mockito.mock(TaxBracketRepository.class);
        var deductions = Mockito.mock(StandardDeductionRepository.class);
        stubSingle2025(brackets, deductions);
        stubMfj2025(brackets, deductions);
        return new FederalTaxCalculator(brackets, deductions);
    }
}
