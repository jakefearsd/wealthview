package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the per-pool return generation (Task 15): the blessed per-account -> balance-
 * weighted-pool aggregation, the override (fixed, no volatility) vs allocation (matrix-blended)
 * split, the nominal conversion, and the empty-pool fallback to the blended portfolio return.
 */
class PortfolioPathGeneratorTest {

    private static final AssetClass[] ORDER = AssetClass.values();

    /** US_STOCK constant at +10% real every year, so allocation-based returns are deterministic. */
    private static final RealReturnMatrix FLAT_US_MATRIX = new RealReturnMatrix(
            new int[]{1, 2},
            ORDER,
            new double[][]{
                    {0.10, 0.10, 0.10, 0.10},
                    {0.10, 0.10, 0.10, 0.10},
            });

    private static HypotheticalAccountInput overrideAccount(String balance, String nominalReturn, String type) {
        return new HypotheticalAccountInput(new BigDecimal(balance), BigDecimal.ZERO,
                new BigDecimal(nominalReturn), type);
    }

    private static HypotheticalAccountInput allocationAccount(String balance, AssetAllocation allocation,
                                                              String type) {
        return new HypotheticalAccountInput(new BigDecimal(balance), BigDecimal.ZERO,
                allocation, Optional.empty(), type);
    }

    private static void assertAllClose(double[] actual, double expected) {
        for (double value : actual) {
            assertThat(value).isEqualTo(expected, within(1e-9));
        }
    }

    private static void assertArraysClose(double[] actual, double[] expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < actual.length; i++) {
            assertThat(actual[i]).isEqualTo(expected[i], within(1e-9));
        }
    }

    @Test
    void generate_overrideAccount_growsAtFixedRealWithNoVolatility() {
        // Override real = (1.05 / 1.00) - 1 = 0.05 at zero inflation; real terms grows at 0.05 directly.
        var model = PoolReturnModel.from(List.of(overrideAccount("100000", "0.05", "taxable")), 0.0);

        var paths = PortfolioPathGenerator.generate(3, 4, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        for (int t = 0; t < 3; t++) {
            assertAllClose(paths.taxableReturns()[t], 0.05);
        }
    }

    @Test
    void generate_allocationAccount_appliesMatrixReturnAsReal() {
        // Real terms: ALL_US against the flat +10% real matrix grows at the real 0.10 directly
        // (no Fisher conversion to nominal), regardless of the inflation assumption.
        var model = PoolReturnModel.from(List.of(allocationAccount("100000", AssetAllocation.ALL_US, "taxable")),
                0.03);

        var paths = PortfolioPathGenerator.generate(2, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        assertAllClose(paths.taxableReturns()[0], 0.10);
    }

    @Test
    void generate_mixedPool_balanceWeightsOverrideAndAllocation() {
        // Taxable pool: 25% override (real 0.05) + 75% allocation (real 0.10) -> pool real 0.0875.
        var model = PoolReturnModel.from(List.of(
                overrideAccount("100000", "0.05", "taxable"),
                allocationAccount("300000", AssetAllocation.ALL_US, "taxable")), 0.0);

        var paths = PortfolioPathGenerator.generate(1, 2, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        assertAllClose(paths.taxableReturns()[0], 0.0875);
    }

    @Test
    void generate_emptyPool_growsAtBlendedPortfolioReturn() {
        // No Roth account: the empty Roth pool falls back to the blended portfolio return, which
        // (all accounts ALL_US) equals the taxable/traditional pool returns.
        var model = PoolReturnModel.from(List.of(
                allocationAccount("100000", AssetAllocation.ALL_US, "taxable"),
                allocationAccount("100000", AssetAllocation.ALL_US, "traditional")), 0.0);

        var paths = PortfolioPathGenerator.generate(2, 3, model, FLAT_US_MATRIX, new Random(7L), 0.0);

        for (int t = 0; t < 2; t++) {
            assertArraysClose(paths.rothReturns()[t], paths.taxableReturns()[t]);
        }
    }

    @Test
    void generate_dispersedMatrix_producesNonDegenerateFan() {
        // Against a dispersed matrix the cumulative portfolio path varies across trials (a real fan).
        var matrix = new RealReturnMatrix(new int[]{1, 2, 3, 4},
                ORDER,
                new double[][]{
                        {0.25, 0.20, 0.02, 0.005},
                        {-0.20, -0.18, 0.05, 0.010},
                        {0.15, 0.12, 0.01, 0.004},
                        {-0.05, -0.08, 0.06, 0.008},
                });
        var stockBond = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 0.7, AssetClass.BOND, 0.3));
        var model = PoolReturnModel.from(List.of(allocationAccount("500000", stockBond, "taxable")), 0.02);

        var paths = PortfolioPathGenerator.generate(200, 25, model, matrix, new Random(42L), 0.0);

        double first = paths.portfolioPaths()[0][25];
        boolean anyDifferent = false;
        for (int t = 1; t < 200; t++) {
            if (Math.abs(paths.portfolioPaths()[t][25] - first) > 1.0) {
                anyDifferent = true;
                break;
            }
        }
        assertThat(anyDifferent).as("terminal balances should disperse across trials").isTrue();
        assertThat(paths.portfolioPaths()[0][0]).isEqualTo(500000.0, within(1e-6));
    }

    // B1 (2026-07-11 audit): the scenario's fee rate must be subtracted uniformly from every
    // pool's per-year real return, for both override-based and allocation-based accounts, at this
    // single choke point.

    @Test
    void generate_overrideAccount_feeRateShiftsReturnDownByExactAmount() {
        var model = PoolReturnModel.from(List.of(overrideAccount("100000", "0.05", "taxable")), 0.0);

        var feeFree = PortfolioPathGenerator.generate(3, 4, model, FLAT_US_MATRIX, new Random(1L), 0.0);
        var feeApplied = PortfolioPathGenerator.generate(3, 4, model, FLAT_US_MATRIX, new Random(1L), 0.0025);

        for (int t = 0; t < 3; t++) {
            assertAllClose(feeApplied.taxableReturns()[t], 0.05 - 0.0025);
            for (int y = 0; y < 4; y++) {
                assertThat(feeFree.taxableReturns()[t][y] - feeApplied.taxableReturns()[t][y])
                        .isEqualTo(0.0025, within(1e-9));
            }
        }
    }

    @Test
    void generate_allocationAccount_feeRateShiftsReturnDownByExactAmount() {
        var model = PoolReturnModel.from(List.of(allocationAccount("100000", AssetAllocation.ALL_US, "taxable")),
                0.03);

        var feeFree = PortfolioPathGenerator.generate(2, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0);
        var feeApplied = PortfolioPathGenerator.generate(2, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0025);

        assertAllClose(feeApplied.taxableReturns()[0], 0.10 - 0.0025);
        for (int t = 0; t < 2; t++) {
            for (int y = 0; y < 3; y++) {
                assertThat(feeFree.taxableReturns()[t][y] - feeApplied.taxableReturns()[t][y])
                        .isEqualTo(0.0025, within(1e-9));
            }
        }
    }

    @Test
    void generate_dispersedMatrix_feeRateShiftsEveryTrialYearUniformly() {
        // A seeded generator against a dispersed matrix: every trial/year in the fee-applied run
        // shifts down by EXACTLY the fee rate versus the fee-0 run -- pinning that the drag is
        // uniform across the whole fan, not just the flat-matrix degenerate case above.
        var matrix = new RealReturnMatrix(new int[]{1, 2, 3, 4},
                ORDER,
                new double[][]{
                        {0.25, 0.20, 0.02, 0.005},
                        {-0.20, -0.18, 0.05, 0.010},
                        {0.15, 0.12, 0.01, 0.004},
                        {-0.05, -0.08, 0.06, 0.008},
                });
        var stockBond = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 0.7, AssetClass.BOND, 0.3));
        var model = PoolReturnModel.from(List.of(allocationAccount("500000", stockBond, "taxable")), 0.02);

        var feeFree = PortfolioPathGenerator.generate(50, 10, model, matrix, new Random(42L), 0.0);
        var feeApplied = PortfolioPathGenerator.generate(50, 10, model, matrix, new Random(42L), 0.0025);

        for (int t = 0; t < 50; t++) {
            for (int y = 0; y < 10; y++) {
                assertThat(feeFree.taxableReturns()[t][y] - feeApplied.taxableReturns()[t][y])
                        .as("trial %d year %d", t, y)
                        .isEqualTo(0.0025, within(1e-9));
            }
        }
    }
}
