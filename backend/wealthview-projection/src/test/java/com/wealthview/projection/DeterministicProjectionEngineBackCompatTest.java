package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Back-compat regression for Task 19 (Phase 1, projection realism v2): a scenario built the
 * LEGACY way — a single account created via the {@code expectedReturn}-only convenience
 * constructor ({@link com.wealthview.core.projection.dto.HypotheticalAccountInput}'s 4-arg
 * ctor, which maps to {@code AssetAllocation.ALL_US} + {@code Optional.of(rate)}, with no
 * explicit per-asset-class allocation) — must still run end-to-end without error.
 *
 * <p>It must NOT reproduce the old pre-realism nominal numbers. The engine now runs entirely in
 * a real (today's-dollars) frame: a nominal expected-return override is deflated at the
 * scenario's own inflation rate — {@code (1+nominal)/(1+inflation) - 1} — exactly like every
 * other cash flow (income, Social Security, spending) in the projection. This test computes that
 * real-terms value explicitly and pins the engine to it, rather than to the old nominal figure.
 */
class DeterministicProjectionEngineBackCompatTest extends DeterministicProjectionEngineTestSupport {

    @Test
    void run_legacyExpectedReturnOverrideNoAllocation_appliesRealTermsGrowthAndCompletes() {
        BigDecimal startBalance = bd("500000.0000");
        BigDecimal nominalOverride = bd("0.0600");
        BigDecimal scenarioInflation = bd("0.0300");

        // Already-retired scenario (retirement date one year in the past) so year 1 has zero
        // contributions and growth applies directly to startBalance, isolating the return math.
        var input = createInput(
                LocalDate.now().minusYears(1), 90, scenarioInflation,
                """
                {"birth_year": %d, "withdrawal_rate": 0.03}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("500000.0000", "0", "0.0600")));

        var result = engine.run(input);

        // Real-terms conversion, mirroring PoolStrategy.realReturnFor: (1+X)/(1+i) - 1, computed
        // independently here (not by calling production code) so this is a true pinning check.
        BigDecimal realReturn = BigDecimal.ONE.add(nominalOverride)
                .divide(BigDecimal.ONE.add(scenarioInflation), 8, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        BigDecimal expectedYear1Growth = startBalance.multiply(realReturn).setScale(4, RoundingMode.HALF_UP);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        assertThat(year1.startBalance()).isEqualByComparingTo(startBalance);
        assertThat(year1.contributions()).isEqualByComparingTo(BigDecimal.ZERO);
        // year-1 growth = startBalance x ((1+0.06)/(1+0.03) - 1) = 500000 x 0.02912621 = 14563.1050
        assertThat(year1.growth()).isEqualByComparingTo(expectedYear1Growth);
        assertThat(expectedYear1Growth).isEqualByComparingTo(bd("14563.1050"));

        // Explicitly NOT the old pre-realism nominal number: growth used to be startBalance x
        // 0.06 = 30000.0000 exactly (nominal override applied with no inflation deflation). That
        // changed BY DESIGN under the real-terms frame, so it must no longer hold.
        assertThat(year1.growth()).isNotEqualByComparingTo(bd("30000.0000"));

        // The projection must complete for every year with no exception and a sensible outcome:
        // birthYear = currentYear - 66, endAge 90 -> 24 projected years.
        assertThat(result.yearlyData()).hasSize(24);
        for (var year : result.yearlyData()) {
            assertThat(year.endBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
        assertThat(result.finalBalance()).isGreaterThan(BigDecimal.ZERO);
    }
}
