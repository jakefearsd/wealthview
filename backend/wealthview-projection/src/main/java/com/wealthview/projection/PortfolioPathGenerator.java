package com.wealthview.projection;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.PoolType;
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
        return generate(trialCount, 0, years, model, matrix, rng, feeRate);
    }

    /**
     * As the six-argument overload, but each trial first accumulates every account through
     * {@code preRetirementYears} on the same bootstrap index sequence that then drives the retirement
     * years: {@code balance = max(0, (balance + contribution) * (1 + real - feeRate))} per account
     * per year. The retirement blend weights and the portfolio path then start from each trial's own
     * balances, which are returned in {@code startBalances}. With zero pre-retirement years this is
     * the original computation and {@code startBalances} is {@code null}.
     */
    static PortfolioReturnPaths generate(int trialCount, int preRetirementYears, int years,
                                         PoolReturnModel model, RealReturnMatrix matrix, Random rng,
                                         double feeRate) {
        if (preRetirementYears < 0) {
            throw new IllegalArgumentException("preRetirementYears must not be negative: " + preRetirementYears);
        }
        double[][] taxable = new double[trialCount][];
        double[][] traditional = new double[trialCount][];
        double[][] roth = new double[trialCount][];
        double[][] portfolioPaths = new double[trialCount][years + 1];
        TrialStartBalances[] starts = preRetirementYears > 0 ? new TrialStartBalances[trialCount] : null;

        var bootstrap = new BlockBootstrapReturnGenerator(DEFAULT_BLOCK_LENGTH, rng);
        int historicalSize = matrix.years().length;

        for (int t = 0; t < trialCount; t++) {
            int[] indexSequence;
            double[] accountBalances;
            if (starts == null) {
                indexSequence = bootstrap.generateIndexSequence(years, historicalSize);
                accountBalances = openingBalances(model.allAccounts());
            } else {
                int[] full = bootstrap.generateIndexSequence(preRetirementYears + years, historicalSize);
                accountBalances = accumulate(model.allAccounts(),
                        Arrays.copyOfRange(full, 0, preRetirementYears), matrix, preRetirementYears, feeRate);
                indexSequence = Arrays.copyOfRange(full, preRetirementYears, preRetirementYears + years);
                starts[t] = TrialStartBalances.of(model.allAccounts(), accountBalances);
            }

            double[] taxableBalances = poolBalances(model, accountBalances, PoolType.TAXABLE);
            double[] traditionalBalances = poolBalances(model, accountBalances, PoolType.TRADITIONAL);
            double[] rothBalances = poolBalances(model, accountBalances, PoolType.ROTH);

            double[] portfolioReal = poolRealReturns(
                    model.allAccounts(), accountBalances, sum(accountBalances),
                    indexSequence, matrix, years, null,
                    feeRate);
            taxable[t] = poolRealReturns(
                    model.taxable(), taxableBalances, sum(taxableBalances),
                    indexSequence, matrix, years, portfolioReal, feeRate);
            traditional[t] = poolRealReturns(
                    model.traditional(), traditionalBalances, sum(traditionalBalances),
                    indexSequence, matrix, years, portfolioReal, feeRate);
            roth[t] = poolRealReturns(
                    model.roth(), rothBalances, sum(rothBalances),
                    indexSequence, matrix, years, portfolioReal, feeRate);

            portfolioPaths[t][0] = starts == null ? model.totalBalance() : starts[t].total();
            for (int y = 0; y < years; y++) {
                portfolioPaths[t][y + 1] = portfolioPaths[t][y] * (1 + portfolioReal[y]);
            }
        }
        return new PortfolioReturnPaths(taxable, traditional, roth, portfolioPaths, starts);
    }

    private static double[] openingBalances(List<AccountReturnSource> accounts) {
        double[] balances = new double[accounts.size()];
        for (int i = 0; i < balances.length; i++) {
            balances[i] = accounts.get(i).balance();
        }
        return balances;
    }

    // Summing in account order from 0 reproduces PoolReturnModel.from's running totals exactly, so the
    // zero-prefix path stays bit-identical.
    private static double sum(double... values) {
        double total = 0;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    /** Balances of the accounts in one pool, in the pool list's order (the order of {@code allAccounts}). */
    private static double[] poolBalances(PoolReturnModel model, double[] accountBalances, PoolType pool) {
        var accounts = model.allAccounts();
        int count = 0;
        for (var account : accounts) {
            if (account.poolType() == pool) {
                count++;
            }
        }
        double[] balances = new double[count];
        int next = 0;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).poolType() == pool) {
                balances[next++] = accountBalances[i];
            }
        }
        return balances;
    }

    /** Grows every account through the pre-retirement years: contribute, then grow net of fees, floored at 0. */
    private static double[] accumulate(List<AccountReturnSource> accounts, int[] preSequence,
                                       RealReturnMatrix matrix, int preYears, double feeRate) {
        double[] balances = new double[accounts.size()];
        for (int i = 0; i < balances.length; i++) {
            var account = accounts.get(i);
            double[] real = account.overrideBased()
                    ? PortfolioReturnResolver.fixed(preYears, account.overrideReal())
                    : PortfolioReturnResolver.resolveReal(preSequence, account.allocation(), matrix);
            double balance = account.balance();
            for (int y = 0; y < preYears; y++) {
                balance = Math.max(0, (balance + account.contribution()) * (1 + real[y] - feeRate));
            }
            balances[i] = balance;
        }
        return balances;
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
     * <p>Accounts are weighted by their balance at retirement start ({@code balances}, aligned with
     * {@code accounts}); when the pool's accounts all open at zero they
     * are weighted by annual contribution, else equally (API #23) — so a zero-balance pool grows at
     * its OWN accounts' return, exactly like the deterministic {@code PoolStrategy}, instead of a
     * zero weight.
     */
    private static double[] poolRealReturns(List<AccountReturnSource> accounts, double[] balances,
                                            double poolBalance, int[] indexSequence, RealReturnMatrix matrix,
                                            int years, @Nullable double[] fallback, double feeRate) {
        if (accounts.isEmpty()) {
            return fallback != null ? fallback : new double[years];
        }
        double[] weights = blendWeights(accounts, balances, poolBalance);
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

    /**
     * Normalized blend weights: balance share, else positive-contribution share, else equal
     * (non-empty list). A negative contribution weighs zero, as in the deterministic
     * {@code PoolStrategy}, so it can neither cancel the total nor extrapolate the blend.
     */
    private static double[] blendWeights(List<AccountReturnSource> accounts, double[] balances,
                                         double poolBalance) {
        double[] weights = new double[accounts.size()];
        double contributions = 0;
        for (var account : accounts) {
            contributions += Math.max(account.contribution(), 0);
        }
        for (int i = 0; i < weights.length; i++) {
            var account = accounts.get(i);
            if (poolBalance > 0) {
                weights[i] = balances[i] / poolBalance;
            } else if (contributions > 0) {
                weights[i] = Math.max(account.contribution(), 0) / contributions;
            } else {
                weights[i] = 1.0 / weights.length;
            }
        }
        return weights;
    }
}
