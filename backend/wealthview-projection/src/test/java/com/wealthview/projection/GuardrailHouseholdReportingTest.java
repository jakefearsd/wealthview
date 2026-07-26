package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Household task 8, items 1 + 3: reporting coherence over the first-death transition.
 *
 * <p>T6 pre-scaled the ESSENTIAL FLOOR by the survivor spending factor from the transition year
 * (the success-metric choke point). T8 finishes the picture on the REPORTING side: the
 * discretionary/recommended schedule shown to the user, and the corridor derived from it, now scale
 * the same way — see {@link GuardrailResponseBuilder#scaleForReporting}. This class direction-verifies
 * that landed behind a generously-funded household fixture (no essential-floor capacity clamp, no
 * year-over-year smoothing), where the discretionary search's pre-scale output is provably CONSTANT
 * across the whole one-phase horizon ({@link SustainabilitySearch#allocateSpending}'s uniform fill),
 * so the post-transition ratio to the pre-transition value is exactly the survivor spending factor —
 * a clean, MC-noise-free proof that item 3's fix is wired end-to-end through corridor derivation too.
 */
class GuardrailHouseholdReportingTest {

    private final MonteCarloSpendingOptimizer optimizer =
            new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

    private static final BigDecimal SURVIVOR_FACTOR = new BigDecimal("0.75");

    /** Primary born 1962 (retires 2030 at 68), spouse born 1968; primary dies at 82 (2044 — first
     * death, mid-horizon, index 14); spouse dies at 90 (2058 — beyond the 2052 horizon, so the
     * second death never truncates and every post-transition year through the end is survivor-phase). */
    private GuardrailOptimizationInput generouslyFundedHouseholdInput(BigDecimal maxAdjRate) {
        List<ProjectionAccountInput> accounts = List.of(
                account(new BigDecimal("4000000"), "taxable", "joint"),
                account(new BigDecimal("2000000"), "traditional", "primary"),
                account(new BigDecimal("1500000"), "traditional", "spouse"),
                account(new BigDecimal("1500000"), "roth", "primary"),
                account(new BigDecimal("1000000"), "roth", "spouse"));
        return GuardrailOptimizationInputBuilder.builder()
                .withBirthYear(1962)
                .withAccounts(accounts)
                .withReturnMean(null)
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
                .withSpouseDeathAge(90)
                .withSurvivorSpendingFactor(SURVIVOR_FACTOR)
                .build();
    }

    private static HypotheticalAccountInput account(BigDecimal balance, String type, String owner) {
        return new HypotheticalAccountInput(balance, BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.empty(), balance, type, owner);
    }

    @Test
    void optimize_generouslyFundedHousehold_postTransitionFloorAndDiscretionaryScaleBySurvivorFactor() {
        var result = optimizer.optimize(generouslyFundedHouseholdInput(BigDecimal.ZERO));
        List<GuardrailYearlySpending> yearly = result.yearlySpending();

        // primary dies 2044, retirement 2030 -> transition index 14.
        assertThat(yearly).hasSize(22);
        int transitionIdx = 14;
        assertThat(yearly.get(transitionIdx).year()).isEqualTo(2044);

        double preFloor = yearly.get(0).essentialFloor().doubleValue();
        double preDisc = yearly.get(0).discretionary().doubleValue();
        // A $10M household against a $30k essential floor is never capacity-clamped: constant real
        // floor every pre-transition year.
        assertThat(preFloor).isEqualTo(30000.0, within(0.01));

        // Pre-transition years are all identical (one phase, uniform search fill, no smoothing).
        for (int y = 0; y < transitionIdx; y++) {
            assertThat(yearly.get(y).essentialFloor().doubleValue())
                    .as("floor year %d", y).isEqualTo(preFloor, within(0.01));
            assertThat(yearly.get(y).discretionary().doubleValue())
                    .as("discretionary year %d", y).isEqualTo(preDisc, within(0.01));
        }

        // Post-transition years scale by the survivor factor (0.75) applied uniformly.
        double expectedPostFloor = preFloor * SURVIVOR_FACTOR.doubleValue();
        double expectedPostDisc = preDisc * SURVIVOR_FACTOR.doubleValue();
        for (int y = transitionIdx; y < yearly.size(); y++) {
            assertThat(yearly.get(y).essentialFloor().doubleValue())
                    .as("floor year %d", y).isEqualTo(expectedPostFloor, within(0.01));
            assertThat(yearly.get(y).discretionary().doubleValue())
                    .as("discretionary year %d", y).isEqualTo(expectedPostDisc, within(0.01));
            assertThat(yearly.get(y).recommended().doubleValue())
                    .as("recommended year %d", y)
                    .isEqualTo((preFloor + preDisc) * SURVIVOR_FACTOR.doubleValue(), within(0.05));
        }

        // Corridor derivation from the scaled plan: bracket the (scaled) recommended in EVERY year,
        // pre- and post-transition alike.
        for (var row : yearly) {
            double recommended = row.recommended().doubleValue();
            assertThat(row.corridorLow().doubleValue())
                    .as("corridor low <= recommended, year %d", row.year())
                    .isLessThanOrEqualTo(recommended + 0.01);
            assertThat(row.corridorHigh().doubleValue())
                    .as("corridor high >= recommended, year %d", row.year())
                    .isGreaterThanOrEqualTo(recommended - 0.01);
        }
    }

    @Test
    void optimize_generouslyFundedHouseholdWithAdaptiveRules_corridorStaysCoherentAndSuccessRatesComputed() {
        // T16/T24 pathway: year-over-year smoothing + the with-rules disclosure pass, layered on top
        // of the SAME household transition/reporting scaling. Verifies the search/gate/adaptation
        // machinery consumes the T6-scaled floors and the T8-scaled reporting schedule without
        // incoherence (corridor still brackets recommended every year) or failure.
        var result = optimizer.optimize(generouslyFundedHouseholdInput(new BigDecimal("0.10")));

        assertThat(result.successProbability()).isNotNull();
        assertThat(result.disclosure().successProbabilityWithRules()).isNotNull();
        assertThat(result.disclosure().successProbabilityWithRules())
                .isGreaterThanOrEqualTo(result.successProbability().subtract(new BigDecimal("1e-9")));

        List<GuardrailYearlySpending> yearly = result.yearlySpending();
        assertThat(yearly).hasSize(22);
        for (var row : yearly) {
            double recommended = row.recommended().doubleValue();
            assertThat(row.corridorLow().doubleValue()).isLessThanOrEqualTo(recommended + 0.01);
            assertThat(row.corridorHigh().doubleValue()).isGreaterThanOrEqualTo(recommended - 0.01);
        }

        // Post-transition recommended spending is materially lower than the last pre-transition year
        // (direction-verify item 3's fix under the T16 smoothing path too, without pinning an exact
        // ratio since smoothing can move the pre-scale schedule year to year).
        double lastPreTransition = yearly.get(13).recommended().doubleValue();
        double firstPostTransition = yearly.get(14).recommended().doubleValue();
        assertThat(firstPostTransition).isLessThan(lastPreTransition);
    }
}
