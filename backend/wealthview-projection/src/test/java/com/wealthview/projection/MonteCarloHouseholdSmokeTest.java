package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household task 6: end-to-end smoke test of the household-aware Monte Carlo optimizer on a stressed
 * age-gap couple (owner-split traditional/Roth pools, an appreciated joint taxable pool, a mid-horizon
 * first death, and a second death that truncates the horizon). Verifies the full pipeline runs, the
 * adaptive-rules success rate never falls below the no-adaptation rate, and a fixed seed is
 * reproducible. The byte-identical single-person anchors live in the characterization/golden suites.
 */
class MonteCarloHouseholdSmokeTest {

    private final MonteCarloSpendingOptimizer optimizer =
            new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

    /** A stressed age-gap household: primary born 1962 (retires 2030 at 68), spouse born 1968; primary
     * dies at 82 (2044 — first death, mid-horizon), spouse at 82 (2050 — second death, truncates). */
    private GuardrailOptimizationInput householdInput(Long seed, BigDecimal maxAdjRate) {
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
                .withSeed(seed)
                .withMaxAnnualAdjustmentRate(maxAdjRate)
                .withFilingStatus("married_filing_jointly")
                .withWithdrawalOrder("taxable_first")
                .withBaseYear(2025)
                .withGateOnAdaptiveRules(true)
                // Household task 6 fields: spouse present, mid-horizon first death, community-property off.
                .withSpouseBirthYear(1968)
                .withPrimaryDeathAge(82)
                .withSpouseDeathAge(82)
                .withSurvivorSpendingFactor(new BigDecimal("0.75"))
                .build();
    }

    private static HypotheticalAccountInput account(BigDecimal balance, BigDecimal basis,
                                                    String type, String owner) {
        return new HypotheticalAccountInput(balance, BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.empty(), basis, type, owner);
    }

    @Test
    void optimize_stressedHousehold_completesWithNonEmptySchedule() {
        var result = optimizer.optimize(householdInput(42L, new BigDecimal("0.10")));

        assertThat(result).isNotNull();
        assertThat(result.yearlySpending()).isNotEmpty();
        assertThat(result.yearlySpending()).allSatisfy(year ->
                assertThat(year.recommended()).isGreaterThanOrEqualTo(BigDecimal.ZERO));
    }

    @Test
    void optimize_stressedHousehold_withRulesSuccessNotBelowNoAdaptation() {
        var result = optimizer.optimize(householdInput(42L, new BigDecimal("0.10")));

        // T24 monotonicity carries through the household economics: the adaptive-rules success rate
        // (populated because maxAnnualAdjustmentRate > 0) is never worse than the fixed-schedule
        // (no-adaptation) rate.
        BigDecimal withRules = result.disclosure().successProbabilityWithRules();
        assertThat(withRules).isNotNull();
        assertThat(withRules).isGreaterThanOrEqualTo(result.successProbability().subtract(new BigDecimal("1e-9")));
    }

    @Test
    void optimize_stressedHousehold_sameSeedIsReproducible() {
        GuardrailProfileResponse a = optimizer.optimize(householdInput(7L, new BigDecimal("0.10")));
        GuardrailProfileResponse b = optimizer.optimize(householdInput(7L, new BigDecimal("0.10")));

        assertThat(b.successProbability()).isEqualByComparingTo(a.successProbability());
        assertThat(b.yearlySpending()).hasSameSizeAs(a.yearlySpending());
        for (int i = 0; i < a.yearlySpending().size(); i++) {
            assertThat(b.yearlySpending().get(i).recommended())
                    .isEqualByComparingTo(a.yearlySpending().get(i).recommended());
        }
    }
}
