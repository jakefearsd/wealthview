package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;

/**
 * Blends account-level real returns into the per-pool and whole-portfolio returns {@link PoolStrategy}
 * grows its pools at. Static-only and {@link BigDecimal}-based (it feeds the golden-file wire contract).
 */
final class PoolReturnBlender {

    private PoolReturnBlender() {
    }

    /**
     * Computes the real (inflation-adjusted), fee-adjusted return for a single account. When a
     * nominal expected-return override is present it is converted to real via
     * {@code (1+nominal)/(1+inflation) - 1}; otherwise the account's allocation is blended against
     * the supplied capital-market geometric means (already real). The scenario's annual all-in
     * fee/expense-ratio drag (audit B1) is then subtracted uniformly — a fee is a fee, whether the
     * account's return is allocation-derived or a fixed override — so both paths converge through
     * this single choke point before returning.
     */
    static BigDecimal realReturnFor(ProjectionAccountInput acct,
                                    Map<AssetClass, Double> geoMeans, BigDecimal inflationRate,
                                    BigDecimal feeRate) {
        BigDecimal grossReal;
        if (acct.expectedReturnOverride().isPresent()) {
            BigDecimal nominal = acct.expectedReturnOverride().get();
            grossReal = BigDecimal.ONE.add(nominal)
                    .divide(BigDecimal.ONE.add(inflationRate), PoolStrategy.SCALE + 4, PoolStrategy.ROUNDING)
                    .subtract(BigDecimal.ONE);
        } else {
            double blended = 0.0;
            for (var e : acct.allocation().weights().entrySet()) {
                Double g = geoMeans.get(e.getKey());
                if (g != null) {
                    blended += e.getValue().doubleValue() * g;
                }
            }
            grossReal = BigDecimal.valueOf(blended).setScale(PoolStrategy.SCALE + 4, PoolStrategy.ROUNDING);
        }
        return grossReal.subtract(feeRate);
    }

    /**
     * Audit C1: the balance-weighted "equity share" (us_stock + intl_stock weight) of a set of
     * taxable accounts, used to split the taxable pool's annual yield between qualified-dividend
     * treatment (the equity share, at {@code dividendYield}) and ordinary-interest treatment (the
     * remaining bond+cash share, at {@code interestYield} — {@link AssetClass#CASH} is treated as
     * bond-like ordinary interest at the same proxy rate; a distinct money-market yield is not
     * modeled). Computed ONCE from each account's INITIAL balance/allocation, exactly like {@link
     * #realReturnFor}'s per-account return — it does not track allocation drift as the pool's
     * composition or relative balances change over the projection horizon.
     *
     * <p>Returns {@link BigDecimal#ONE} (100% equity, 0% bond/cash) when the accounts carry no
     * balance at all — the split is then moot (there is no taxable value to distribute a yield
     * against), and defaulting to the ALL_US-equivalent share keeps this degenerate case on the
     * same code path as the byte-identical backward-compat anchor.
     */
    static BigDecimal taxableEquityShare(List<ProjectionAccountInput> taxableAccounts) {
        BigDecimal totalBalance = sumInitialBalances(taxableAccounts);
        if (totalBalance.signum() <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        for (var acct : taxableAccounts) {
            BigDecimal weights0 = acct.allocation().weights().getOrDefault(AssetClass.US_STOCK, BigDecimal.ZERO);
            BigDecimal equityWeight = weights0.add(
                    acct.allocation().weights().getOrDefault(AssetClass.INTL_STOCK, BigDecimal.ZERO));
            weighted = weighted.add(acct.initialBalance().multiply(equityWeight));
        }
        return weighted.divide(totalBalance, PoolStrategy.SCALE + 4, PoolStrategy.ROUNDING);
    }

    /**
     * The balance-weighted real, fee-adjusted return across the given accounts. Each account's real
     * return is resolved via {@link #realReturnFor}. {@link PoolStrategy#create} resolves it over the
     * WHOLE portfolio (every account, every pool type) as the deterministic engine's default growth
     * assumption, and per account-type group as that pool's own return. Reused by the
     * Roth-conversion optimizer's guardrail-flow return resolution (audit C4) so the two engines'
     * notion of "the scenario's expected real return" stays in lockstep instead of drifting apart.
     *
     * <p>Weights are the accounts' opening balances. When every opening balance is zero (a
     * brand-new account, the classic young accumulator) the weights fall back to the annual
     * contributions, and when those are zero too, to an equal-weight mean -- a zero-balance pool
     * used to weight every account by zero and grow at 0% for the entire horizon (API #23), even
     * as contributions poured in. An empty account list returns zero (nothing to blend); see
     * {@link #poolWeightedReturn} for the pool-level fallback.
     */
    static BigDecimal blendedRealReturn(List<ProjectionAccountInput> accounts,
                                        Map<AssetClass, Double> geoMeans,
                                        BigDecimal inflationRate,
                                        BigDecimal feeRate) {
        if (accounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> weights = blendWeights(accounts);
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (int i = 0; i < accounts.size(); i++) {
            BigDecimal weight = weights.get(i);
            weightedSum = weightedSum.add(
                    weight.multiply(realReturnFor(accounts.get(i), geoMeans, inflationRate, feeRate)));
            totalWeight = totalWeight.add(weight);
        }
        return weightedSum.divide(totalWeight, PoolStrategy.SCALE + 4, PoolStrategy.ROUNDING);
    }

    /**
     * The per-pool returns for {@code assetReturns} (the geometric means, or one year's returns): the same
     * allocation blend, fee and empty-pool fallback {@link PoolStrategy#create} builds with.
     */
    static PoolStrategy.PoolReturns poolReturnsFor(List<ProjectionAccountInput> accounts,
                                                   Map<AssetClass, Double> assetReturns,
                                                   BigDecimal inflationRate, BigDecimal feeRate) {
        BigDecimal household = blendedRealReturn(accounts, assetReturns, inflationRate, feeRate);
        return poolReturns(groupByPool(accounts), household, assetReturns, inflationRate, feeRate);
    }

    static Map<PoolType, List<ProjectionAccountInput>> groupByPool(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .collect(Collectors.groupingBy(ProjectionAccountInput::poolType,
                        () -> new EnumMap<>(PoolType.class), Collectors.toList()));
    }

    static PoolStrategy.PoolReturns poolReturns(Map<PoolType, List<ProjectionAccountInput>> grouped,
                                                BigDecimal household, Map<AssetClass, Double> assetReturns,
                                                BigDecimal inflationRate, BigDecimal feeRate) {
        return new PoolStrategy.PoolReturns(
                poolWeightedReturn(grouped.getOrDefault(PoolType.TAXABLE, List.of()), household,
                        assetReturns, inflationRate, feeRate),
                poolWeightedReturn(grouped.getOrDefault(PoolType.TRADITIONAL, List.of()), household,
                        assetReturns, inflationRate, feeRate),
                poolWeightedReturn(grouped.getOrDefault(PoolType.ROTH, List.of()), household,
                        assetReturns, inflationRate, feeRate));
    }

    private static BigDecimal sumInitialBalances(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .map(ProjectionAccountInput::initialBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Per-account blend weights for {@link #blendedRealReturn}: opening balances when any is
     * positive, else positive annual contributions when any is positive, else one each (equal
     * weight). A negative contribution (a planned pre-retirement withdrawal) weighs zero: weighting
     * by it could cancel the positive ones to a zero total (a divide by zero) or extrapolate the
     * blend past every account's own return. Never all-zero for a non-empty list, so the blend is
     * always defined.
     */
    private static List<BigDecimal> blendWeights(List<ProjectionAccountInput> accounts) {
        if (sumInitialBalances(accounts).signum() > 0) {
            return accounts.stream().map(ProjectionAccountInput::initialBalance).toList();
        }
        List<BigDecimal> contributionWeights = accounts.stream()
                .map(account -> account.annualContribution().max(BigDecimal.ZERO))
                .toList();
        if (contributionWeights.stream().anyMatch(weight -> weight.signum() > 0)) {
            return contributionWeights;
        }
        return accounts.stream().map(account -> BigDecimal.ONE).toList();
    }

    /**
     * The real return one account-type pool grows at: its accounts' weighted return (see
     * {@link #blendedRealReturn}), or -- for a pool with NO accounts at all, such as the Roth
     * pool that Roth conversions create in a scenario with no Roth account, or the taxable pool
     * that receives reinvested RMD/surplus cash with no taxable account -- {@code householdReturn},
     * the whole portfolio's weighted return. Money that lands in an account-less pool is the
     * household's money, so it earns the household's blended return rather than 0% (API #5: a
     * traditional-only converter's Roth used to sit at 0% for decades).
     */
    private static BigDecimal poolWeightedReturn(List<ProjectionAccountInput> accounts,
                                                  BigDecimal householdReturn,
                                                  Map<AssetClass, Double> geoMeans,
                                                  BigDecimal inflationRate,
                                                  BigDecimal feeRate) {
        if (accounts.isEmpty()) {
            return householdReturn;
        }
        return blendedRealReturn(accounts, geoMeans, inflationRate, feeRate);
    }
}
