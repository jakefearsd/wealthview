package com.wealthview.projection;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.BondMaturityReturnsProvider.MaturityReturnMatrix;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.BondMaturity;
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
// GodClass: the tent recording joins the path generator's other per-trial steps; they all read the same
// bootstrap index sequence and are only meaningful together.
@SuppressWarnings("PMD.GodClass")
final class PortfolioPathGenerator {

    private static final double DEFAULT_BLOCK_LENGTH = 5.0;
    private static final int POOLS = PoolType.values().length;

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
        return generate(trialCount, preRetirementYears, years, model, matrix, rng, feeRate, null);
    }

    /**
     * As the seven-argument overload; with {@code maturities} non-null it also records {@link TentPaths}
     * for the Monte Carlo bond tent: each trial's sleeve returns (the maturity matrix rows picked by the
     * same index sequence, net of {@code feeRate}) and, when there are pre-retirement years, the pool totals
     * before and after each year's growth. It makes no extra {@code rng} call, so every other output is
     * identical to the call without it.
     */
    // NPathComplexity: one trial loop assembles every path set, each tent array guarded by the same null
    // switch; splitting it would scatter the single bootstrap index sequence the whole class hinges on.
    @SuppressWarnings("PMD.NPathComplexity")
    static PortfolioReturnPaths generate(int trialCount, int preRetirementYears, int years,
                                         PoolReturnModel model, RealReturnMatrix matrix, Random rng,
                                         double feeRate, @Nullable MaturityReturnMatrix maturities) {
        if (preRetirementYears < 0) {
            throw new IllegalArgumentException("preRetirementYears must not be negative: " + preRetirementYears);
        }
        double[][] taxable = new double[trialCount][];
        double[][] traditional = new double[trialCount][];
        double[][] roth = new double[trialCount][];
        double[][] portfolioPaths = new double[trialCount][years + 1];
        TrialStartBalances[] starts = preRetirementYears > 0 ? new TrialStartBalances[trialCount] : null;
        double[][] sleeveReturns = maturities == null ? null : new double[trialCount][];
        double[][] poolsBefore = maturities == null || starts == null ? null : new double[trialCount][];
        double[][] poolsAfter = poolsBefore == null ? null : new double[trialCount][];

        var bootstrap = new BlockBootstrapReturnGenerator(DEFAULT_BLOCK_LENGTH, rng);
        int historicalSize = matrix.years().length;

        for (int t = 0; t < trialCount; t++) {
            int[] indexSequence;
            double[] accountBalances;
            if (starts == null) {
                indexSequence = bootstrap.generateIndexSequence(years, historicalSize);
                accountBalances = openingBalances(model.allAccounts());
                if (sleeveReturns != null) {
                    sleeveReturns[t] = sleeveReturnRow(maturities, indexSequence, feeRate);
                }
            } else {
                int[] full = bootstrap.generateIndexSequence(preRetirementYears + years, historicalSize);
                double[] before = null;
                double[] after = null;
                if (poolsBefore != null) {
                    before = new double[preRetirementYears * POOLS];
                    after = new double[preRetirementYears * POOLS];
                    poolsBefore[t] = before;
                    poolsAfter[t] = after;
                    sleeveReturns[t] = sleeveReturnRow(maturities, full, feeRate);
                }
                accountBalances = accumulate(model.allAccounts(),
                        Arrays.copyOfRange(full, 0, preRetirementYears), matrix, preRetirementYears, feeRate,
                        before, after);
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
        var tentPaths = sleeveReturns == null ? null : new TentPaths(sleeveReturns, poolsBefore, poolsAfter);
        return new PortfolioReturnPaths(taxable, traditional, roth, portfolioPaths, starts, tentPaths);
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

    /**
     * One trial's sleeve returns, {@code [yearFromToday * 4 + maturity]}: the maturity matrix rows picked by the
     * trial's index sequence, net of the fee.
     */
    private static double[] sleeveReturnRow(MaturityReturnMatrix maturities, int[] sequence, double feeRate) {
        int count = BondMaturity.values().length;
        double[] row = new double[sequence.length * count];
        for (int y = 0; y < sequence.length; y++) {
            double[] source = maturities.realReturns()[sequence[y]];
            for (int m = 0; m < count; m++) {
                row[y * count + m] = source[m] - feeRate;
            }
        }
        return row;
    }

    /**
     * Grows every account through the pre-retirement years: contribute, then grow net of fees, floored at 0.
     * When {@code poolBefore}/{@code poolAfter} are non-null, each year's pool totals (after contributions,
     * before growth, and after growth) are added into {@code [year * 3 + pool]}.
     */
    // UseVarargs: the two optional recording arrays are year-by-pool grids, not variable arguments.
    @SuppressWarnings("PMD.UseVarargs")
    private static double[] accumulate(List<AccountReturnSource> accounts, int[] preSequence,
                                       RealReturnMatrix matrix, int preYears, double feeRate,
                                       @Nullable double[] poolBefore, @Nullable double[] poolAfter) {
        double[] balances = new double[accounts.size()];
        for (int i = 0; i < balances.length; i++) {
            var account = accounts.get(i);
            double[] real = account.overrideBased()
                    ? PortfolioReturnResolver.fixed(preYears, account.overrideReal())
                    : PortfolioReturnResolver.resolveReal(preSequence, account.allocation(), matrix);
            double balance = account.balance();
            int pool = account.poolType().ordinal();
            for (int y = 0; y < preYears; y++) {
                double contributed = balance + account.contribution();
                balance = Math.max(0, contributed * (1 + real[y] - feeRate));
                if (poolBefore != null) {
                    poolBefore[y * POOLS + pool] += contributed;
                    poolAfter[y * POOLS + pool] += balance;
                }
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
     * <p>Accounts are weighted by the {@code balances} passed in, aligned with {@code accounts}: the
     * opening balances when there are no pre-retirement years, otherwise the trial's own balances at
     * retirement start. When the pool's accounts all sit at zero they
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
