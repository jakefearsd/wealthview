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
     * sequences plus a blended cumulative total-portfolio balance path (also real). {@code feeRate}
     * is the scenario's annual all-in investment fee/expense-ratio drag (audit B1) — subtracted
     * uniformly from every pool's per-year real return in {@link #poolRealReturns}, the single
     * choke point covering both allocation-derived and fixed-override accounts.
     */
    static PortfolioReturnPaths generate(int trialCount, int years, PoolReturnModel model,
                                         RealReturnMatrix matrix, Random rng, double feeRate) {
        double[][] taxable = new double[trialCount][];
        double[][] traditional = new double[trialCount][];
        double[][] roth = new double[trialCount][];
        double[][] portfolioPaths = new double[trialCount][years + 1];

        var bootstrap = new BlockBootstrapReturnGenerator(DEFAULT_BLOCK_LENGTH, rng);
        int historicalSize = matrix.years().length;

        for (int t = 0; t < trialCount; t++) {
            int[] indexSequence = bootstrap.generateIndexSequence(years, historicalSize);

            double[] portfolioReal = poolRealReturns(
                    model.allAccounts(), model.totalBalance(), indexSequence, matrix, years, null, feeRate);
            taxable[t] = poolRealReturns(
                    model.taxable(), model.taxableBalance(), indexSequence, matrix, years, portfolioReal, feeRate);
            traditional[t] = poolRealReturns(
                    model.traditional(), model.traditionalBalance(), indexSequence, matrix, years, portfolioReal,
                    feeRate);
            roth[t] = poolRealReturns(
                    model.roth(), model.rothBalance(), indexSequence, matrix, years, portfolioReal, feeRate);

            portfolioPaths[t][0] = model.totalBalance();
            for (int y = 0; y < years; y++) {
                portfolioPaths[t][y + 1] = portfolioPaths[t][y] * (1 + portfolioReal[y]);
            }
        }
        return new PortfolioReturnPaths(taxable, traditional, roth, portfolioPaths);
    }

    /**
     * Weighted REAL return sequence for one pool. A pool with NO accounts grows at the blended
     * portfolio return {@code fallback} — e.g. a Roth pool that only receives Roth conversions has
     * no starting accounts but must still grow the converted dollars at a sensible rate. For the
     * portfolio blend itself the fallback is {@code null} (zero returns only when the run has no
     * accounts at all). {@code fallback} already carries {@code feeRate} (it is itself the output of
     * a prior call to this method), so only the non-fallback branch subtracts it — once per pool,
     * uniformly across override-based and allocation-based accounts alike (audit B1).
     *
     * <p>Accounts are weighted by opening balance; when the pool's accounts all open at zero they
     * are weighted by annual contribution, else equally (API #23) — so a zero-balance pool grows at
     * its OWN accounts' return, exactly like the deterministic {@code PoolStrategy}, instead of a
     * zero weight.
     */
    private static double[] poolRealReturns(List<AccountReturnSource> accounts, double poolBalance,
                                            int[] indexSequence, RealReturnMatrix matrix,
                                            int years, @Nullable double[] fallback, double feeRate) {
        if (accounts.isEmpty()) {
            return fallback != null ? fallback : new double[years];
        }
        double[] weights = blendWeights(accounts, poolBalance);
        double[] real = new double[years];
        for (int i = 0; i < accounts.size(); i++) {
            var account = accounts.get(i);
            double[] accountReal = account.overrideBased()
                    ? PortfolioReturnResolver.fixed(years, account.overrideReal())
                    : PortfolioReturnResolver.resolveReal(indexSequence, account.allocation(), matrix);
            double weight = weights[i];
            for (int y = 0; y < years; y++) {
                real[y] += weight * accountReal[y];
            }
        }
        for (int y = 0; y < years; y++) {
            real[y] -= feeRate;
        }
        return real;
    }

    /** Normalized blend weights: balance share, else contribution share, else equal (non-empty list). */
    private static double[] blendWeights(List<AccountReturnSource> accounts, double poolBalance) {
        double[] weights = new double[accounts.size()];
        double contributions = 0;
        for (var account : accounts) {
            contributions += account.contribution();
        }
        for (int i = 0; i < weights.length; i++) {
            var account = accounts.get(i);
            if (poolBalance > 0) {
                weights[i] = account.balance() / poolBalance;
            } else if (contributions > 0) {
                weights[i] = account.contribution() / contributions;
            } else {
                weights[i] = 1.0 / weights.length;
            }
        }
        return weights;
    }
}
