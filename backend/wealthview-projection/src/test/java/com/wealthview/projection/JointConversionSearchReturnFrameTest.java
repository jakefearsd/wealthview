package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Audit C4 (frame mismatch): {@link JointConversionSearch#resolveReturnMean} must resolve the
 * {@link ConversionSimulator} growth assumption in the SAME real, fee-adjusted frame the simulator
 * prices its constant-real bracket ceilings in (see {@link ConversionSimulator}'s class Javadoc).
 *
 * <p>Pins the two resolution branches directly against the underlying production resolvers
 * ({@link PoolStrategy#blendedRealReturn}, {@link CapitalMarketAssumptionsProvider#geometricMeansOf})
 * rather than a re-derived literal, so this test fails only if {@code resolveReturnMean} actually
 * diverges from "the engine's own resolver output" — not if the CMA fixture data or blend algorithm
 * changes for unrelated reasons.
 */
class JointConversionSearchReturnFrameTest {

    private static final double INFLATION_RATE = 0.03;
    private static final double FEE_RATE = 0.0025;

    private final JointConversionSearch search =
            new JointConversionSearch(null, new SustainabilitySearch(new TrialSimulator()));

    private final List<ProjectionAccountInput> accounts = List.of(
            new HypotheticalAccountInput(new BigDecimal("600000"), BigDecimal.ZERO, null, "traditional"),
            new HypotheticalAccountInput(new BigDecimal("400000"), BigDecimal.ZERO, null, "taxable"));

    private GuardrailOptimizationInput inputWithReturnMean(BigDecimal returnMean) {
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1963, 90, BigDecimal.valueOf(INFLATION_RATE),
                accounts, List.of(),
                new BigDecimal("40000"), BigDecimal.ZERO,
                returnMean,
                500, new BigDecimal("0.95"), phases, 42L,
                BigDecimal.ZERO, null, 0,
                0, BigDecimal.ZERO, "single", "taxable_first",
                true, new BigDecimal("0.22"), new BigDecimal("0.12"), 5, null, null,
                null, BigDecimal.valueOf(FEE_RATE));
    }

    @Test
    void resolveReturnMean_noExplicitOverride_equalsScenarioBlendedFeeAdjustedRealReturn() {
        var input = inputWithReturnMean(null);
        var matrix = ProjectionTestFixtures.TEST_CMA_MATRIX;

        double resolved = search.resolveReturnMean(input, INFLATION_RATE, FEE_RATE, matrix);

        // Independently invoke the SAME production resolvers the deterministic engine uses for its
        // own default growth assumption -- this is "the engine's own resolver output", not a
        // hand-derived literal.
        var geoMeans = CapitalMarketAssumptionsProvider.geometricMeansOf(matrix);
        double expected = PoolStrategy.blendedRealReturn(accounts, geoMeans,
                BigDecimal.valueOf(INFLATION_RATE), BigDecimal.valueOf(FEE_RATE)).doubleValue();

        assertThat(resolved).isEqualTo(expected, offset(1e-12));
        // Sanity: materially below the pre-fix hardcoded 10% nominal-as-real default -- the whole
        // point of the fix.
        assertThat(resolved).isLessThan(0.10);
    }

    @Test
    void resolveReturnMean_explicitNominalOverride_fisherConvertsThenSubtractsFee() {
        var input = inputWithReturnMean(new BigDecimal("0.08"));
        var matrix = ProjectionTestFixtures.TEST_CMA_MATRIX;

        double resolved = search.resolveReturnMean(input, INFLATION_RATE, FEE_RATE, matrix);

        double expected = (1 + 0.08) / (1 + INFLATION_RATE) - 1 - FEE_RATE;

        assertThat(resolved).isEqualTo(expected, offset(1e-12));
        assertThat(resolved).isEqualTo(0.046043689320388326, offset(1e-9));
    }

    @Test
    void resolveReturnMean_explicitOverride_ignoresAccountsAndMatrix() {
        // An explicit override is a user-declared nominal rate -- it must not depend on the
        // scenario's allocation blend at all, only on the Fisher conversion + fee.
        var input = inputWithReturnMean(new BigDecimal("0.05"));

        double resolvedWithTestMatrix = search.resolveReturnMean(
                input, INFLATION_RATE, FEE_RATE, ProjectionTestFixtures.TEST_CMA_MATRIX);

        double expected = (1 + 0.05) / (1 + INFLATION_RATE) - 1 - FEE_RATE;
        assertThat(resolvedWithTestMatrix).isEqualTo(expected, offset(1e-12));
    }
}
