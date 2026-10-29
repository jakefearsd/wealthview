package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.BondMaturityReturnsProvider.MaturityReturnMatrix;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // Audit C10: turning on include_depression_years widens the CapitalMarketAssumptionsProvider
    // window (see CapitalMarketAssumptionsProviderTest), which changes nothing about
    // PortfolioPathGenerator itself -- it just receives a matrix with more (and more extreme) rows
    // to block-bootstrap from. These two tests pin that consequence directly at the bootstrap seam:
    // a matrix WITHOUT the Depression-era tail can never sample worse than -36% real equity
    // (bounded by construction), while the SAME matrix plus the real, hand-verified 1931 row (see
    // .superpowers/sdd/audit2/t17-report.md) reliably does, at a fixed seed.

    /** Ten representative post-1972-style years; worst row is -30% -- never breaches -36%. */
    private static final RealReturnMatrix DEFAULT_WINDOW_LIKE_MATRIX = new RealReturnMatrix(
            new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
            ORDER,
            new double[][]{
                    { 0.15,  0.10,  0.02,  0.005},
                    {-0.10, -0.08,  0.06,  0.010},
                    { 0.22,  0.18,  0.01, -0.005},
                    {-0.30, -0.25, -0.02,  0.002},
                    { 0.12,  0.09,  0.05,  0.006},
                    { 0.28,  0.24,  0.00, -0.010},
                    { 0.02, -0.03,  0.07,  0.012},
                    {-0.05,  0.03,  0.04,  0.004},
                    { 0.18,  0.14,  0.03,  0.003},
                    {-0.02,  0.01,  0.06,  0.007},
            });

    /**
     * Same ten rows plus one Depression-era row -- 1931's real us_stock return, -0.382857
     * (Damodaran nominal -43.84% / CPI avg-of-year deflation -9.0%; see t17 report), which is
     * exactly what {@code R__seed_asset_class_returns.sql} carries for that year.
     */
    private static final RealReturnMatrix EXTENDED_WINDOW_MATRIX = new RealReturnMatrix(
            new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1931},
            ORDER,
            new double[][]{
                    { 0.15,  0.10,  0.02,  0.005},
                    {-0.10, -0.08,  0.06,  0.010},
                    { 0.22,  0.18,  0.01, -0.005},
                    {-0.30, -0.25, -0.02,  0.002},
                    { 0.12,  0.09,  0.05,  0.006},
                    { 0.28,  0.24,  0.00, -0.010},
                    { 0.02, -0.03,  0.07,  0.012},
                    {-0.05,  0.03,  0.04,  0.004},
                    { 0.18,  0.14,  0.03,  0.003},
                    {-0.02,  0.01,  0.06,  0.007},
                    {-0.382857, -0.382857,  0.070769,  0.124286},
            });

    @Test
    void generate_defaultWindowLikeMatrix_neverSamplesBelowMinus36Percent() {
        var model = PoolReturnModel.from(List.of(allocationAccount("500000", AssetAllocation.ALL_US, "taxable")),
                0.02);

        var paths = PortfolioPathGenerator.generate(500, 20, model, DEFAULT_WINDOW_LIKE_MATRIX, new Random(2026L),
                0.0);

        for (double[] trial : paths.taxableReturns()) {
            for (double yearReturn : trial) {
                assertThat(yearReturn).isGreaterThanOrEqualTo(-0.36);
            }
        }
    }

    @Test
    void generate_extendedWindowMatrix_seededRunSamplesAYearWorseThanMinus36Percent() {
        var model = PoolReturnModel.from(List.of(allocationAccount("500000", AssetAllocation.ALL_US, "taxable")),
                0.02);

        var paths = PortfolioPathGenerator.generate(500, 20, model, EXTENDED_WINDOW_MATRIX, new Random(2026L), 0.0);

        boolean sampledDepressionTail = false;
        for (double[] trial : paths.taxableReturns()) {
            for (double yearReturn : trial) {
                if (yearReturn < -0.36) {
                    sampledDepressionTail = true;
                    break;
                }
            }
        }
        assertThat(sampledDepressionTail)
                .as("500 trials x 20 years should sample the added 1931-style row at least once")
                .isTrue();
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

    // API #23 / #5, Monte Carlo side: a pool whose accounts all open at $0 must use its OWN
    // accounts' return (weighted by contribution, else equally), not a zero balance weight.

    @Test
    void generate_zeroBalancePoolWithAccounts_growsAtItsOwnAccountsReturn() {
        var model = PoolReturnModel.from(List.of(
                overrideAccount("100000", "0.03", "taxable"),
                new HypotheticalAccountInput(BigDecimal.ZERO, new BigDecimal("7000"),
                        new BigDecimal("0.07"), "roth")), 0.0);

        var paths = PortfolioPathGenerator.generate(1, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        assertAllClose(paths.rothReturns()[0], 0.07);
    }

    @Test
    void generate_allBalancesZero_portfolioUsesEqualWeightMeanNotZero() {
        var model = PoolReturnModel.from(List.of(
                overrideAccount("0", "0.05", "taxable"),
                overrideAccount("0", "0.07", "roth")), 0.0);

        var paths = PortfolioPathGenerator.generate(1, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        assertAllClose(paths.taxableReturns()[0], 0.05);
        assertAllClose(paths.traditionalReturns()[0], 0.06);
    }

    @Test
    void generate_zeroBalancePoolWithANegativeContribution_givesItNoWeight() {
        // Raw contribution weights 2 / -1 extrapolated the blend to 2*0.05 - 0.09 = 0.01.
        var model = PoolReturnModel.from(List.of(
                new HypotheticalAccountInput(BigDecimal.ZERO, new BigDecimal("10000"),
                        new BigDecimal("0.05"), "roth"),
                new HypotheticalAccountInput(BigDecimal.ZERO, new BigDecimal("-5000"),
                        new BigDecimal("0.09"), "roth")), 0.0);

        var paths = PortfolioPathGenerator.generate(1, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        assertAllClose(paths.rothReturns()[0], 0.05);
    }

    @Test
    void generate_zeroBalancePoolWithOnlyNegativeContributions_usesEqualWeightMean() {
        var model = PoolReturnModel.from(List.of(
                new HypotheticalAccountInput(BigDecimal.ZERO, new BigDecimal("-1000"),
                        new BigDecimal("0.05"), "roth"),
                new HypotheticalAccountInput(BigDecimal.ZERO, new BigDecimal("-3000"),
                        new BigDecimal("0.07"), "roth")), 0.0);

        var paths = PortfolioPathGenerator.generate(1, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        assertAllClose(paths.rothReturns()[0], 0.06);
    }

    private static HypotheticalAccountInput contributingAccount(String balance, String contribution,
                                                                String type) {
        return new HypotheticalAccountInput(new BigDecimal(balance), new BigDecimal(contribution),
                AssetAllocation.ALL_US, Optional.empty(), type);
    }

    @Test
    void generate_zeroPreRetirementYears_identicalToSixArgOverload() {
        var model = PoolReturnModel.from(List.of(
                contributingAccount("100000", "5000", "taxable"),
                contributingAccount("50000", "0", "traditional")), 0.02);

        var sixArg = PortfolioPathGenerator.generate(20, 10, model, ProjectionTestFixtures.TEST_CMA_MATRIX,
                new Random(7L), 0.001);
        var sevenArg = PortfolioPathGenerator.generate(20, 0, 10, model, ProjectionTestFixtures.TEST_CMA_MATRIX,
                new Random(7L), 0.001);

        assertThat(sevenArg.taxableReturns()).isDeepEqualTo(sixArg.taxableReturns());
        assertThat(sevenArg.traditionalReturns()).isDeepEqualTo(sixArg.traditionalReturns());
        assertThat(sevenArg.rothReturns()).isDeepEqualTo(sixArg.rothReturns());
        assertThat(sevenArg.portfolioPaths()).isDeepEqualTo(sixArg.portfolioPaths());
        assertThat(sevenArg.startBalances()).isNull();
    }

    @Test
    void generate_preRetirementYears_contributesBeforeGrowthEachYear() {
        var model = PoolReturnModel.from(List.of(contributingAccount("100000", "10000", "taxable")), 0.0);

        var paths = PortfolioPathGenerator.generate(3, 2, 4, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        for (int t = 0; t < 3; t++) {
            assertThat(paths.startBalances()[t].taxable()).isEqualTo(144_100.0, within(1e-6));
            assertThat(paths.portfolioPaths()[t][0]).isEqualTo(144_100.0, within(1e-6));
        }
    }

    @Test
    void generate_preRetirementYears_feeReducesEachYearsGrowth() {
        var model = PoolReturnModel.from(List.of(contributingAccount("100000", "10000", "taxable")), 0.0);

        var paths = PortfolioPathGenerator.generate(3, 2, 4, model, FLAT_US_MATRIX, new Random(1L), 0.01);

        for (int t = 0; t < 3; t++) {
            assertThat(paths.startBalances()[t].taxable()).isEqualTo(141_591.0, within(1e-6));
        }
    }

    @Test
    void generate_preRetirementYears_overrideAccount_sameStartInEveryTrial() {
        var model = PoolReturnModel.from(List.of(overrideAccount("100000", "0.05", "taxable")), 0.0);

        var paths = PortfolioPathGenerator.generate(10, 3, 4, model, ProjectionTestFixtures.TEST_CMA_MATRIX,
                new Random(3L), 0.0);

        for (int t = 0; t < 10; t++) {
            assertThat(paths.startBalances()[t].taxable()).isEqualTo(115_762.5, within(1e-6));
        }
    }

    @Test
    void generate_preRetirementYears_dispersedMatrix_startBalancesVaryAcrossTrials() {
        var model = PoolReturnModel.from(List.of(allocationAccount("100000", AssetAllocation.ALL_US, "taxable")),
                0.0);

        var paths = PortfolioPathGenerator.generate(200, 10, 5, model, ProjectionTestFixtures.TEST_CMA_MATRIX,
                new Random(42L), 0.0);

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (var start : paths.startBalances()) {
            min = Math.min(min, start.total());
            max = Math.max(max, start.total());
        }
        assertThat(min).isLessThan(max);
    }

    @Test
    void generate_preRetirementYears_zeroOpeningBalanceWithContributions_growsPerTrial() {
        var model = PoolReturnModel.from(List.of(contributingAccount("0", "5000", "taxable")), 0.0);

        var paths = PortfolioPathGenerator.generate(3, 2, 4, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        for (int t = 0; t < 3; t++) {
            assertThat(paths.startBalances()[t].taxable()).isEqualTo(11_550.0, within(1e-6));
            assertAllClose(paths.taxableReturns()[t], 0.10);
        }
    }

    @Test
    void generate_preRetirementYears_negativeContribution_neverBelowZero() {
        var model = PoolReturnModel.from(List.of(contributingAccount("1000", "-5000", "taxable")), 0.0);

        var paths = PortfolioPathGenerator.generate(3, 3, 4, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        for (int t = 0; t < 3; t++) {
            assertThat(paths.startBalances()[t].taxable()).isEqualTo(0.0);
            assertThat(paths.portfolioPaths()[t][0]).isEqualTo(0.0);
        }
    }

    @Test
    void generate_preRetirementYears_poolBlendWeightsUseTrialRetirementBalances() {
        var model = PoolReturnModel.from(List.of(
                overrideAccount("100000", "0.0", "taxable"),
                contributingAccount("0", "100000", "taxable")), 0.0);

        var paths = PortfolioPathGenerator.generate(2, 1, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        for (int t = 0; t < 2; t++) {
            for (double value : paths.taxableReturns()[t]) {
                assertThat(value).isEqualTo(11_000.0 / 210_000.0, within(1e-12));
            }
        }
    }

    /** Replays scripted bootstrap draws: every block terminates, and {@code nextInt} pops the script. */
    private static final class ScriptedRandom extends Random {
        private static final long serialVersionUID = 1L;
        private final transient ArrayDeque<Integer> script;

        ScriptedRandom(int... draws) {
            super(0L);
            script = new ArrayDeque<>();
            for (int draw : draws) {
                script.add(draw);
            }
        }

        @Override
        public int nextInt(int bound) {
            return script.removeFirst();
        }

        @Override
        public double nextDouble() {
            return 0.0;
        }
    }

    @Test
    void generate_preRetirementYears_prefixIndicesDriveAccumulationAndSuffixDriveRetirement() {
        // Row 0 = +10% and row 1 = -10% for every asset class. The scripted full sequence over
        // pre(2) + years(2) is [0, 0, 1, 1]: the FIRST two indices must grow the account to
        // 100000 * 1.1 * 1.1, and the REMAINING two must supply the retirement returns of -10%.
        var matrix = new RealReturnMatrix(new int[]{1, 2}, ORDER, new double[][]{
                {0.10, 0.10, 0.10, 0.10},
                {-0.10, -0.10, -0.10, -0.10},
        });
        var model = PoolReturnModel.from(List.of(allocationAccount("100000", AssetAllocation.ALL_US, "taxable")),
                0.0);

        var paths = PortfolioPathGenerator.generate(1, 2, 2, model, matrix, new ScriptedRandom(0, 0, 1, 1), 0.0);

        assertThat(paths.startBalances()[0].total()).isEqualTo(121_000.0, within(1e-6));
        assertArraysClose(paths.taxableReturns()[0], new double[]{-0.10, -0.10});
    }

    @Test
    void generate_negativePreRetirementYears_throwsIllegalArgument() {
        var model = PoolReturnModel.from(List.of(overrideAccount("100000", "0.05", "taxable")), 0.0);

        assertThatThrownBy(() -> PortfolioPathGenerator.generate(1, -1, 4, model, FLAT_US_MATRIX,
                new Random(1L), 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final MaturityReturnMatrix TWO_ROW_MATURITIES = new MaturityReturnMatrix(
            new int[]{1, 2}, BondMaturity.values(),
            new double[][]{{0.01, 0.02, 0.03, 0.04}, {0.05, 0.06, 0.07, 0.08}});

    private static final RealReturnMatrix TWO_ROW_MIXED_MATRIX = new RealReturnMatrix(
            new int[]{1, 2}, ORDER,
            new double[][]{{0.10, 0.02, 0.01, 0.00}, {-0.20, 0.03, 0.02, 0.01}});

    @Test
    void generate_withMaturities_leavesEveryExistingOutputIdentical() {
        var model = PoolReturnModel.from(List.of(
                allocationAccount("100000", AssetAllocation.ALL_US, "taxable"),
                allocationAccount("50000", AssetAllocation.ALL_US, "traditional")), 0.0);

        var plain = PortfolioPathGenerator.generate(5, 3, 10, model, TWO_ROW_MIXED_MATRIX, new Random(7L), 0.001);
        var withTent = PortfolioPathGenerator.generate(5, 3, 10, model, TWO_ROW_MIXED_MATRIX, new Random(7L), 0.001,
                TWO_ROW_MATURITIES);

        assertThat(withTent.taxableReturns()).isEqualTo(plain.taxableReturns());
        assertThat(withTent.traditionalReturns()).isEqualTo(plain.traditionalReturns());
        assertThat(withTent.rothReturns()).isEqualTo(plain.rothReturns());
        assertThat(withTent.portfolioPaths()).isEqualTo(plain.portfolioPaths());
        assertThat(withTent.startBalances()).isEqualTo(plain.startBalances());
        assertThat(plain.tentPaths()).isNull();
        assertThat(withTent.tentPaths()).isNotNull();
    }

    @Test
    void generate_withMaturities_sleeveReturnsAreTheTrialsMatrixRowsLessFee() {
        var model = PoolReturnModel.from(List.of(allocationAccount("100000", AssetAllocation.ALL_US, "taxable")),
                0.0);
        var oneRow = new MaturityReturnMatrix(new int[]{1}, BondMaturity.values(),
                new double[][]{{0.01, 0.02, 0.03, 0.04}});
        var oneRowMatrix = new RealReturnMatrix(new int[]{1}, ORDER, new double[][]{{0.1, 0.0, 0.0, 0.0}});

        var paths = PortfolioPathGenerator.generate(2, 1, 3, model, oneRowMatrix, new Random(3L), 0.005, oneRow);

        for (int t = 0; t < 2; t++) {
            assertThat(paths.tentPaths().sleeveReturnsOf(t)).hasSize(16);
            for (int y = 0; y < 4; y++) {
                for (var m : BondMaturity.values()) {
                    assertThat(paths.tentPaths().sleeveReturn(t, y, m))
                            .isEqualTo(oneRow.realReturns()[0][m.ordinal()] - 0.005, within(1e-12));
                }
            }
        }
    }

    @Test
    void generate_preRetirement_recordsPoolTotalsBeforeAndAfterGrowth() {
        var account = new HypotheticalAccountInput(new BigDecimal("100000"), new BigDecimal("10000"),
                AssetAllocation.ALL_US, Optional.empty(), "taxable");
        var model = PoolReturnModel.from(List.of(account), 0.0);

        var paths = PortfolioPathGenerator.generate(1, 2, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0,
                TWO_ROW_MATURITIES);

        var tent = paths.tentPaths();
        assertThat(tent.poolBeforeGrowth(0, 0, PoolType.TAXABLE)).isEqualTo(110_000, within(1e-6));
        assertThat(tent.poolAfterGrowth(0, 0, PoolType.TAXABLE)).isEqualTo(121_000, within(1e-6));
        assertThat(tent.poolBeforeGrowth(0, 1, PoolType.TAXABLE)).isEqualTo(131_000, within(1e-6));
        assertThat(tent.poolAfterGrowth(0, 1, PoolType.TAXABLE)).isEqualTo(144_100, within(1e-6));
        assertThat(tent.poolBeforeGrowth(0, 0, PoolType.ROTH)).isZero();
    }

    @Test
    void generate_preRetirementNegativeContribution_recordsFlooredBeforeBalance() {
        var healthy = new HypotheticalAccountInput(new BigDecimal("100000"), BigDecimal.ZERO,
                AssetAllocation.ALL_US, Optional.empty(), "taxable");
        var drained = new HypotheticalAccountInput(new BigDecimal("10000"), new BigDecimal("-30000"),
                AssetAllocation.ALL_US, Optional.empty(), "taxable");
        var model = PoolReturnModel.from(List.of(healthy, drained), 0.0);

        var paths = PortfolioPathGenerator.generate(1, 1, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0,
                TWO_ROW_MATURITIES);

        var tent = paths.tentPaths();
        double before = tent.poolBeforeGrowth(0, 0, PoolType.TAXABLE);
        double after = tent.poolAfterGrowth(0, 0, PoolType.TAXABLE);
        assertThat(before).isEqualTo(100_000, within(1e-6));
        assertThat(after / before - 1).isEqualTo(0.10, within(1e-9));
    }

    @Test
    void generate_withoutMaturities_hasNoTentPaths() {
        var model = PoolReturnModel.from(List.of(allocationAccount("100000", AssetAllocation.ALL_US, "taxable")),
                0.0);

        var paths = PortfolioPathGenerator.generate(2, 1, 3, model, FLAT_US_MATRIX, new Random(1L), 0.0);

        assertThat(paths.tentPaths()).isNull();
    }

    @Test
    void generate_noPreRetirementYearsWithMaturities_recordsSleevesWithoutPools() {
        var model = PoolReturnModel.from(List.of(allocationAccount("100000", AssetAllocation.ALL_US, "taxable")),
                0.0);
        var oneRow = new MaturityReturnMatrix(new int[]{1}, BondMaturity.values(),
                new double[][]{{0.01, 0.02, 0.03, 0.04}});
        var oneRowMatrix = new RealReturnMatrix(new int[]{1}, ORDER, new double[][]{{0.1, 0.0, 0.0, 0.0}});

        var paths = PortfolioPathGenerator.generate(2, 0, 3, model, oneRowMatrix, new Random(3L), 0.005, oneRow);

        var tent = paths.tentPaths();
        assertThat(tent.poolsBeforeGrowth()).isNull();
        assertThat(tent.poolsAfterGrowth()).isNull();
        assertThat(tent.sleeveReturnsOf(1)).hasSize(12);
        assertThat(tent.sleeveReturn(1, 2, BondMaturity.LONG)).isEqualTo(0.04 - 0.005, within(1e-12));
        assertThatThrownBy(() -> tent.poolBeforeGrowth(0, 0, PoolType.TAXABLE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tent.poolAfterGrowth(0, 0, PoolType.TAXABLE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generate_misalignedMaturityMatrix_throwsIllegalArgument() {
        var model = PoolReturnModel.from(List.of(allocationAccount("100000", AssetAllocation.ALL_US, "taxable")),
                0.0);
        var oneRow = new MaturityReturnMatrix(new int[]{1}, BondMaturity.values(),
                new double[][]{{0.01, 0.02, 0.03, 0.04}});

        assertThatThrownBy(() -> PortfolioPathGenerator.generate(1, 1, 3, model, FLAT_US_MATRIX, new Random(1L),
                0.0, oneRow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Maturity return matrix has 1 rows");
    }
}
