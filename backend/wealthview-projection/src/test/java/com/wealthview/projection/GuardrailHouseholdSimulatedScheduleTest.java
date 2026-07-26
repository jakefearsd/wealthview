package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * HP2 — the guardrail optimizer now SIMULATES the survivor-scaled discretionary schedule during its
 * terminal/reporting passes, so the certified success rate measures exactly the schedule DISPLAYED
 * to the user, not the un-scaled draws the search's sustainability gate was built against.
 *
 * <p>Pre-HP2 (T8): the search + terminal simulation drew the FULL discretionary post-transition,
 * while only a reporting-only clone was scaled by the survivor factor for {@code yearly_spending}.
 * A stressed household therefore reported {@code successProbability} pinned at exactly its confidence
 * level (0.90) — the terminal pass reproducing the search gate against draws the user is NOT shown —
 * yet displayed a reduced (×0.75) post-transition schedule. HP2 scales the schedule ONCE at
 * {@link MonteCarloSpendingOptimizer#allocateAndSmooth} (mirroring the essential-floor pre-scaling in
 * {@link OptimizationContextBuilder}); {@link GuardrailResponseBuilder} then simulates AND reports
 * that one array. Net: post-transition draws drop to the scaled level, so the certified success rate
 * RISES above the gate and the reported schedule equals the simulated one.
 *
 * <p>Numbers below are deterministic (seed 42, {@link ProjectionTestFixtures#TEST_CMA_MATRIX}); the
 * pre-HP2 baselines quoted in comments were captured from the immediately-prior pipeline.
 */
class GuardrailHouseholdSimulatedScheduleTest {

    private final MonteCarloSpendingOptimizer optimizer =
            new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

    private static final double SURVIVOR_FACTOR = 0.75;
    /** Search-gate confidence level; the pre-HP2 terminal pass reproduced this exactly. */
    private static final double CONFIDENCE = 0.90;
    private static final int TRANSITION_IDX = 14;

    private static HypotheticalAccountInput account(BigDecimal balance, BigDecimal basis,
                                                    String type, String owner) {
        return new HypotheticalAccountInput(balance, BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.empty(), basis, type, owner);
    }

    /** Stressed age-gap household: primary born 1962 (retires 2030 at 68), spouse born 1968; primary
     * dies at 82 (2044 — first death, transition index 14), spouse at 82 (2050 — second death). A
     * $630k portfolio against a $40k essential floor makes the post-transition draw level materially
     * affect the certified success rate. */
    private GuardrailOptimizationInput stressedHousehold(BigDecimal maxAdjRate) {
        List<ProjectionAccountInput> accounts = List.of(
                account(new BigDecimal("300000"), new BigDecimal("200000"), "taxable", "joint"),
                account(new BigDecimal("150000"), new BigDecimal("150000"), "traditional", "primary"),
                account(new BigDecimal("100000"), new BigDecimal("100000"), "traditional", "spouse"),
                account(new BigDecimal("50000"), new BigDecimal("50000"), "roth", "primary"),
                account(new BigDecimal("30000"), new BigDecimal("30000"), "roth", "spouse"));
        return GuardrailOptimizationInputBuilder.builder()
                .withBirthYear(1962)
                .withAccounts(accounts)
                .withEssentialFloor(new BigDecimal("40000"))
                .withTrialCount(300)
                .withConfidenceLevel(new BigDecimal("0.90"))
                .withPhases(List.of(new GuardrailPhaseInput("Retirement", 68, null, 1)))
                .withMaxAnnualAdjustmentRate(maxAdjRate)
                .withFilingStatus("married_filing_jointly")
                .withWithdrawalOrder("taxable_first")
                .withBaseYear(2025)
                .withGateOnAdaptiveRules(true)
                .withSpouseBirthYear(1968)
                .withPrimaryDeathAge(82)
                .withSpouseDeathAge(82)
                .withSurvivorSpendingFactor(new BigDecimal("0.75"))
                .build();
    }

    /**
     * Test 1 (simulated == displayed + direction): the terminal pass now draws the SCALED
     * post-transition schedule, so the certified success rate exceeds the search gate. Pre-HP2 the
     * terminal pass drew the FULL discretionary and reproduced the gate exactly (0.9000).
     */
    @Test
    void optimize_stressedHousehold_certifiedSuccessMeasuresDisplayedScaledSchedule() {
        var result = optimizer.optimize(stressedHousehold(BigDecimal.ZERO));
        List<GuardrailYearlySpending> yearly = result.yearlySpending();
        assertThat(yearly).hasSize(22);
        assertThat(yearly.get(TRANSITION_IDX).year()).isEqualTo(2044);

        double preDisc = yearly.get(0).discretionary().doubleValue();
        double postDisc = yearly.get(TRANSITION_IDX).discretionary().doubleValue();
        // The displayed schedule is unchanged from pre-HP2: pre-transition 5685.4123, post ×0.75.
        assertThat(preDisc).isEqualTo(5685.4123, within(0.01));
        assertThat(postDisc).isEqualTo(preDisc * SURVIVOR_FACTOR, within(0.01));

        // The whole point: the reported success rate reflects the SCALED (displayed) draws. Only
        // possible if the terminal simulation consumed the reduced post-transition schedule — the
        // un-scaled draws the search gated on reproduce the gate exactly (0.9000, pre-HP2), so a
        // strictly higher rate is the signature of simulated == displayed.
        double success = result.successProbability().doubleValue();
        assertThat(success).isGreaterThan(CONFIDENCE);
        assertThat(success).isEqualTo(0.9067, within(1e-4));   // pre-HP2: 0.9000

        // Less drawn post-transition => higher ending balances (both median and the 10th percentile
        // move up from their pre-HP2 values).
        assertThat(result.medianFinalBalance().doubleValue())
                .isGreaterThan(480002.94)                       // pre-HP2 median
                .isEqualTo(493270.3721, within(0.5));
        assertThat(result.percentile10Final().doubleValue())
                .isGreaterThan(0.0)                             // pre-HP2 p10 was 0
                .isEqualTo(8181.0855, within(0.5));
    }

    /**
     * Test 2 (no double-scale): the factor is applied EXACTLY once end-to-end — post-transition
     * discretionary is 0.75× the pre-transition level, not 0.75² (0.5625×). A regression that scaled
     * both upstream (HP2) and again in the reporting clone (the removed T8 path) would show 0.5625.
     */
    @Test
    void optimize_stressedHousehold_discretionaryScaledExactlyOnce() {
        var result = optimizer.optimize(stressedHousehold(BigDecimal.ZERO));
        List<GuardrailYearlySpending> yearly = result.yearlySpending();

        double preDisc = yearly.get(0).discretionary().doubleValue();
        double postDisc = yearly.get(TRANSITION_IDX).discretionary().doubleValue();
        // Tolerance absorbs the 4-dp rounding of both reported values; still nowhere near a
        // double-scale (0.5625).
        assertThat(postDisc / preDisc).isEqualTo(SURVIVOR_FACTOR, within(1e-5));
        // Exact single-scale value (double-scale would be 3198.0444).
        assertThat(postDisc).isEqualTo(4264.0592, within(0.01));

        // Every post-transition year carries the same single-scaled level (uniform phase, no
        // smoothing); every pre-transition year carries the un-scaled level.
        for (int y = 0; y < yearly.size(); y++) {
            double expected = y < TRANSITION_IDX ? preDisc : preDisc * SURVIVOR_FACTOR;
            assertThat(yearly.get(y).discretionary().doubleValue())
                    .as("discretionary year %d", y).isEqualTo(expected, within(0.01));
        }
    }

    /**
     * Test 4 (adaptation coherence): the T16/T24 with-rules disclosure pass consumes the SAME scaled
     * discretionary schedule as the no-rules terminal pass — the corridor still brackets the reported
     * recommended spending every year, the with-rules rate stays ≥ the no-rules rate, and both rise
     * from their pre-HP2 values because less is drawn post-transition.
     */
    @Test
    void optimize_stressedHouseholdAdaptive_withRulesConsumesScaledScheduleCoherently() {
        var result = optimizer.optimize(stressedHousehold(new BigDecimal("0.10")));
        List<GuardrailYearlySpending> yearly = result.yearlySpending();
        assertThat(yearly).hasSize(22);

        double success = result.successProbability().doubleValue();
        BigDecimal withRules = result.disclosure().successProbabilityWithRules();
        assertThat(withRules).isNotNull();
        // With-rules never draws more than the fixed schedule, so its success rate is never worse.
        assertThat(withRules.doubleValue()).isGreaterThanOrEqualTo(success - 1e-9);
        // Both rose vs pre-HP2 (no-rules 0.6433, with-rules 0.9133) because the scaled schedule is
        // now what gets simulated.
        assertThat(success).isGreaterThan(0.6433);
        assertThat(withRules.doubleValue()).isGreaterThan(0.9133 - 1e-9);

        // Corridor brackets the reported recommended in every year (derived from the same scaled plan).
        for (var row : yearly) {
            double recommended = row.recommended().doubleValue();
            assertThat(row.corridorLow().doubleValue())
                    .as("corridor low <= recommended, year %d", row.year())
                    .isLessThanOrEqualTo(recommended + 0.01);
            assertThat(row.corridorHigh().doubleValue())
                    .as("corridor high >= recommended, year %d", row.year())
                    .isGreaterThanOrEqualTo(recommended - 0.01);
        }
        // Post-transition recommended spending steps below the last pre-transition year.
        assertThat(yearly.get(TRANSITION_IDX).recommended().doubleValue())
                .isLessThan(yearly.get(TRANSITION_IDX - 1).recommended().doubleValue());
    }

    /**
     * Test 5 (anchor): a single-person run has no household, so the HP2 scaling seam is a no-op — the
     * schedule and every certified metric are identical to the pre-HP2 pipeline. Guards the
     * byte-identical single-person contract at the optimizer's public boundary.
     */
    @Test
    void optimize_singlePerson_scalingSeamIsNoOp() {
        var accounts = List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(new BigDecimal("1500000"), BigDecimal.ZERO,
                        AssetAllocation.ALL_US, Optional.empty(), new BigDecimal("1500000"),
                        "taxable", "primary"));
        var input = GuardrailOptimizationInputBuilder.builder()
                .withAccounts(accounts)
                .withEssentialFloor(new BigDecimal("40000"))
                .withReturnMean(null)
                .withTrialCount(300)
                .withConfidenceLevel(new BigDecimal("0.90"))
                .withPhases(List.of(new GuardrailPhaseInput("Retirement", 62, null, 1)))
                .withMaxAnnualAdjustmentRate(BigDecimal.ZERO)
                .withFilingStatus("single")
                .withWithdrawalOrder("taxable_first")
                .withBaseYear(2025)
                .withGateOnAdaptiveRules(true)
                // No spouse: single-person, HP2 scaling is a no-op.
                .build();
        GuardrailProfileResponse result = optimizer.optimize(input);

        // Uniform single-phase schedule, no transition drop anywhere.
        List<GuardrailYearlySpending> yearly = result.yearlySpending();
        double disc0 = yearly.get(0).discretionary().doubleValue();
        assertThat(yearly).allSatisfy(row ->
                assertThat(row.discretionary().doubleValue())
                        .as("year %d discretionary flat (no survivor scaling)", row.year())
                        .isEqualTo(disc0, within(0.01)));
    }
}
