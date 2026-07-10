package com.wealthview.projection;

import java.util.ArrayList;
import java.util.List;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.ProjectionAccountInput;

/**
 * Per-account return sources grouped into the taxable / traditional / roth tax pools, plus the
 * whole-portfolio blend, used to generate per-pool Monte Carlo return sequences from a shared
 * block-bootstrap index sequence.
 *
 * <p>The aggregation mirrors the deterministic engine's {@code PoolStrategy}: each account's real
 * return comes from a fixed override (if present) or from blending its allocation against the
 * capital-market matrix; a pool's return is the balance-weighted average of its accounts' returns.
 * Because every account in a trial shares one index sequence, cross-account/pool correlation is
 * preserved.
 */
record PoolReturnModel(
        List<AccountReturnSource> taxable,
        List<AccountReturnSource> traditional,
        List<AccountReturnSource> roth,
        List<AccountReturnSource> allAccounts,
        double taxableBalance, double traditionalBalance, double rothBalance, double totalBalance) {

    /**
     * One account's return source. When {@code overrideBased} is true the account grows at a fixed
     * real return ({@code overrideReal}, a deterministic escape hatch with no volatility); otherwise
     * its {@code allocation} is blended against the matrix per year.
     */
    record AccountReturnSource(double balance, boolean overrideBased, double overrideReal,
                               AssetAllocation allocation) {}

    static PoolReturnModel from(List<? extends ProjectionAccountInput> accounts, double inflationRate) {
        List<AccountReturnSource> taxable = new ArrayList<>();
        List<AccountReturnSource> traditional = new ArrayList<>();
        List<AccountReturnSource> roth = new ArrayList<>();
        List<AccountReturnSource> all = new ArrayList<>();
        double taxableBalance = 0;
        double traditionalBalance = 0;
        double rothBalance = 0;
        double totalBalance = 0;

        for (var account : accounts) {
            double balance = account.initialBalance().doubleValue();
            var source = sourceFor(account, inflationRate, balance);
            all.add(source);
            totalBalance += balance;
            switch (account.accountType()) {
                case PoolStrategy.POOL_TRADITIONAL -> {
                    traditional.add(source);
                    traditionalBalance += balance;
                }
                case PoolStrategy.POOL_ROTH -> {
                    roth.add(source);
                    rothBalance += balance;
                }
                default -> {
                    taxable.add(source);
                    taxableBalance += balance;
                }
            }
        }
        return new PoolReturnModel(taxable, traditional, roth, all,
                taxableBalance, traditionalBalance, rothBalance, totalBalance);
    }

    private static AccountReturnSource sourceFor(ProjectionAccountInput account,
                                                 double inflationRate, double balance) {
        if (account.expectedReturnOverride().isPresent()) {
            double nominal = account.expectedReturnOverride().get().doubleValue();
            double real = (1 + nominal) / (1 + inflationRate) - 1;
            return new AccountReturnSource(balance, true, real, account.allocation());
        }
        return new AccountReturnSource(balance, false, 0.0, account.allocation());
    }
}
