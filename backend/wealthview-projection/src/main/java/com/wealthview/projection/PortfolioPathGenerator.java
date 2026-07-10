package com.wealthview.projection;

import java.util.List;
import java.util.Random;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.projection.PoolReturnModel.AccountReturnSource;

/**
 * Generates Monte Carlo return trajectories for the spending optimizer.
 *
 * <p>Each trial draws ONE block-bootstrap {@code int[]} index sequence into the multi-asset
 * capital-market matrix (shared across all pools/accounts so correlation is preserved). Every
 * account's real return for the trial is then resolved from that sequence — a fixed override real
 * return, or its allocation blended against the sampled matrix rows — and each pool's real return
 * is the balance-weighted average of its accounts' returns. The whole projection runs in REAL
 * (today's-dollars) terms, so pools grow at these real returns directly — no Fisher conversion to
 * nominal — matching the optimizer's constant-real spending/income model.
 *
 * <p>This class is stateless. Determinism is the caller's responsibility — the supplied
 * {@link Random} is the single source of randomness and must be seeded by the caller.
 */
final class PortfolioPathGenerator {

    private static final double DEFAULT_BLOCK_LENGTH = 5.0;

    private PortfolioPathGenerator() {
    }

    /**
     * Runs {@code trialCount} bootstrap trials (no withdrawals) and returns per-pool REAL return
     * sequences plus a blended cumulative total-portfolio balance path (also real).
     */
    static PortfolioReturnPaths generate(int trialCount, int years, PoolReturnModel model,
                                         RealReturnMatrix matrix, Random rng) {
        double[][] taxable = new double[trialCount][];
        double[][] traditional = new double[trialCount][];
        double[][] roth = new double[trialCount][];
        double[][] portfolioPaths = new double[trialCount][years + 1];

        var bootstrap = new BlockBootstrapReturnGenerator(DEFAULT_BLOCK_LENGTH, rng);
        int historicalSize = matrix.years().length;

        for (int t = 0; t < trialCount; t++) {
            int[] indexSequence = bootstrap.generateIndexSequence(years, historicalSize);

            double[] portfolioReal = poolRealReturns(
                    model.allAccounts(), model.totalBalance(), indexSequence, matrix, years, null);
            taxable[t] = poolRealReturns(
                    model.taxable(), model.taxableBalance(), indexSequence, matrix, years, portfolioReal);
            traditional[t] = poolRealReturns(
                    model.traditional(), model.traditionalBalance(), indexSequence, matrix, years, portfolioReal);
            roth[t] = poolRealReturns(
                    model.roth(), model.rothBalance(), indexSequence, matrix, years, portfolioReal);

            portfolioPaths[t][0] = model.totalBalance();
            for (int y = 0; y < years; y++) {
                portfolioPaths[t][y + 1] = portfolioPaths[t][y] * (1 + portfolioReal[y]);
            }
        }
        return new PortfolioReturnPaths(taxable, traditional, roth, portfolioPaths);
    }

    /**
     * Balance-weighted REAL return sequence for one pool. An empty pool (no accounts / zero
     * balance) grows at the blended portfolio return {@code fallback} — e.g. a Roth pool that only
     * receives Roth conversions has no starting accounts but must still grow the converted dollars
     * at a sensible rate. For the portfolio blend itself the fallback is {@code null} (never empty
     * when the run has any balance).
     */
    // UseVarargs: `fallback` is a per-year return array (or null), not a variable argument list —
    // varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private static double[] poolRealReturns(List<AccountReturnSource> accounts, double poolBalance,
                                            int[] indexSequence, RealReturnMatrix matrix,
                                            int years, @Nullable double[] fallback) {
        if (poolBalance <= 0 || accounts.isEmpty()) {
            return fallback != null ? fallback : new double[years];
        }
        double[] real = new double[years];
        for (var account : accounts) {
            double[] accountReal = account.overrideBased()
                    ? PortfolioReturnResolver.fixed(years, account.overrideReal())
                    : PortfolioReturnResolver.resolveReal(indexSequence, account.allocation(), matrix);
            double weight = account.balance() / poolBalance;
            for (int y = 0; y < years; y++) {
                real[y] += weight * accountReal[y];
            }
        }
        return real;
    }
}
