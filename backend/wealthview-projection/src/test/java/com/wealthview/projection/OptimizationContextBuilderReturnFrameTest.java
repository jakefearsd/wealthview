package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Audit C4 (frame mismatch): {@link OptimizationContextBuilder#resolveReturnMean} must resolve the
 * {@link ConversionSimulator} growth assumption in the SAME real, fee-adjusted frame the simulator
 * prices its constant-real bracket ceilings in (see {@link ConversionSimulator}'s class Javadoc).
 * Resolution lives on the context builder (not {@link JointConversionSearch}) so it happens exactly
 * once per run and every consumer — the conversion search AND the response/persistence echo — sees
 * one value, closing the double-Fisher trap.
 *
 * <p>Pins the two resolution branches directly against the underlying production resolvers
 * ({@link PoolStrategy#blendedRealReturn}, {@link CapitalMarketAssumptionsProvider#geometricMeansOf})
 * rather than a re-derived literal, so this test fails only if {@code resolveReturnMean} actually
 * diverges from "the engine's own resolver output" — not if the CMA fixture data or blend algorithm
 * changes for unrelated reasons.
 */
class OptimizationContextBuilderReturnFrameTest {

    private static final double INFLATION_RATE = 0.03;
    private static final double FEE_RATE = 0.0025;

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

        double resolved = OptimizationContextBuilder.resolveReturnMean(
                input, INFLATION_RATE, FEE_RATE, matrix);

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

        double resolved = OptimizationContextBuilder.resolveReturnMean(
                input, INFLATION_RATE, FEE_RATE, matrix);

        double expected = (1 + 0.08) / (1 + INFLATION_RATE) - 1 - FEE_RATE;

        assertThat(resolved).isEqualTo(expected, offset(1e-12));
        assertThat(resolved).isEqualTo(0.046043689320388326, offset(1e-9));
    }

    @Test
    void resolveReturnMean_explicitOverride_ignoresAccountsAndMatrix() {
        // An explicit override is a user-declared nominal rate -- it must not depend on the
        // scenario's allocation blend at all, only on the Fisher conversion + fee.
        var input = inputWithReturnMean(new BigDecimal("0.05"));

        double resolvedWithTestMatrix = OptimizationContextBuilder.resolveReturnMean(
                input, INFLATION_RATE, FEE_RATE, ProjectionTestFixtures.TEST_CMA_MATRIX);

        double expected = (1 + 0.05) / (1 + INFLATION_RATE) - 1 - FEE_RATE;
        assertThat(resolvedWithTestMatrix).isEqualTo(expected, offset(1e-12));
    }

    @Test
    void build_returnMeanOmitted_simCarriesResolvedRateAndResponseEchoesIt() {
        // End-to-end (audit C4 follow-up): with return_mean omitted -- the ONLY case production
        // traffic produces, since the frontend never sends it -- the built context carries the
        // resolved real rate for the conversion search, and the full optimizer response echoes
        // that SAME resolved value (not null, not a 0.10 nominal default). The response value is
        // what GuardrailProfileService persists, so this also pins the persistence input.
        var input = inputWithReturnMean(null);
        var matrix = ProjectionTestFixtures.TEST_CMA_MATRIX;

        var ctx = new OptimizationContextBuilder(null).build(input, matrix);
        double expected = OptimizationContextBuilder.resolveReturnMean(
                input, INFLATION_RATE, FEE_RATE, matrix);

        assertThat(ctx.sim().returnMean()).isEqualTo(expected, offset(1e-12));

        var response = new MonteCarloSpendingOptimizer(null, matrix).optimize(input);
        assertThat(response.returnMean()).isNotNull();
        assertThat(response.returnMean().doubleValue())
                .as("response echoes the resolved effective REAL rate at Money scale (4)")
                .isEqualTo(BigDecimal.valueOf(expected)
                        .setScale(4, RoundingMode.HALF_UP).doubleValue(), offset(1e-9));
    }
}
