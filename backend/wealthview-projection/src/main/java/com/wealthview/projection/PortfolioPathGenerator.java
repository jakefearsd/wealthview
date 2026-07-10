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
 * is the balance-weighted average of its accounts' returns. Real returns are converted to nominal
 * via the Fisher equation so portfolio growth matches the optimizer's nominal spending/income model
 * (the Monte Carlo stays in the nominal frame this task; a real-terms migration is a later change).
 *
 * <p>This class is stateless. Determinism is the caller's responsibility — the supplied
 * {@link Random} is the single source of randomness and must be seeded by the caller.
 */
final class PortfolioPathGenerator {

    private static final double DEFAULT_BLOCK_LENGTH = 5.0;

    private PortfolioPathGenerator() {
    }

    /**
     * Runs {@code trialCount} bootstrap trials (no withdrawals) and returns per-pool nominal return
     * sequences plus a blended cumulative total-portfolio balance path.
     */
    static PortfolioReturnPaths generate(int trialCount, int years, PoolReturnModel model,
                                         RealReturnMatrix matrix, Random rng, double inflationRate) {
        double[][] taxable = new double[trialCount][];
        double[][] traditional = new double[trialCount][];
        double[][] roth = new double[trialCount][];
        double[][] portfolioPaths = new double[trialCount][years + 1];

        var bootstrap = new BlockBootstrapReturnGenerator(DEFAULT_BLOCK_LENGTH, rng);
        int historicalSize = matrix.years().length;

        for (int t = 0; t < trialCount; t++) {
            int[] indexSequence = bootstrap.generateIndexSequence(years, historicalSize);

            double[] portfolioNominal = poolNominalReturns(
                    model.allAccounts(), model.totalBalance(), indexSequence, matrix, inflationRate, years, null);
            taxable[t] = poolNominalReturns(
                    model.taxable(), model.taxableBalance(), indexSequence, matrix, inflationRate, years,
                    portfolioNominal);
            traditional[t] = poolNominalReturns(
                    model.traditional(), model.traditionalBalance(), indexSequence, matrix, inflationRate, years,
                    portfolioNominal);
            roth[t] = poolNominalReturns(
                    model.roth(), model.rothBalance(), indexSequence, matrix, inflationRate, years,
                    portfolioNominal);

            portfolioPaths[t][0] = model.totalBalance();
            for (int y = 0; y < years; y++) {
                portfolioPaths[t][y + 1] = portfolioPaths[t][y] * (1 + portfolioNominal[y]);
            }
        }
        return new PortfolioReturnPaths(taxable, traditional, roth, portfolioPaths);
    }

    /**
     * Balance-weighted nominal return sequence for one pool. An empty pool (no accounts / zero
     * balance) grows at the blended portfolio return {@code fallback} — e.g. a Roth pool that only
     * receives Roth conversions has no starting accounts but must still grow the converted dollars
     * at a sensible rate. For the portfolio blend itself the fallback is {@code null} (never empty
     * when the run has any balance).
     */
    // UseVarargs: `fallback` is a per-year return array (or null), not a variable argument list —
    // varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private static double[] poolNominalReturns(List<AccountReturnSource> accounts, double poolBalance,
                                               int[] indexSequence, RealReturnMatrix matrix,
                                               double inflationRate, int years, @Nullable double[] fallback) {
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
        double[] nominal = new double[years];
        for (int y = 0; y < years; y++) {
            nominal[y] = toNominal(real[y], inflationRate);
        }
        return nominal;
    }

    /** Converts a real (CPI-adjusted) return to a nominal return via the Fisher equation. */
    static double toNominal(double realReturn, double inflationRate) {
        return (1 + realReturn) * (1 + inflationRate) - 1;
    }
}
