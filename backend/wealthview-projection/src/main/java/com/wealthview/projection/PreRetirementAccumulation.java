package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;

/**
 * Computes the EXPECTED-path accounts at RETIREMENT (API #22): the scalar balances, bases and pool
 * gating the Monte Carlo optimizer, its tax context and the Roth-conversion optimizer read.
 *
 * <p>Monte Carlo trials themselves now start in the base year and accumulate through their own pre-retirement
 * markets (bond tent plan 1, {@link PortfolioPathGenerator}); this class supplies the expected-path scalars alongside
 * them. Before that change the optimizer started every trial from these seeded balances at retirement; originally it
 * used TODAY's balances, so a 29-year-old retiring in 33 years with $45,500/yr of contributions was judged on a
 * ~$30,000 portfolio while the deterministic projection had $3.87M at retirement. This rolls every account forward
 * from {@link GuardrailOptimizationInput#baseYear()} to the retirement year in the same order as the deterministic
 * engine's accumulation years: each year the annual contribution is added, then the balance grows at the account's
 * real, fee-adjusted return ({@link PoolStrategy#realReturnFor}, the per-account rate the deterministic engine blends
 * into its pool returns). Taxable contributions enter at cost and accumulation growth stays unrealized (audit C8), so
 * cost basis rises by the contributions only.
 *
 * <p>This is not an exact replay of the deterministic balances. Each account grows here at its OWN
 * return, while the deterministic engine grows each pool at one return fixed at the start, weighted
 * by the accounts' opening balances (by their contributions when every opening balance is zero).
 * The two agree when a pool's accounts share a return. In a pool that mixes allocations, for example
 * a funded 40/60 401(k) next to a new all-stock IRA that receives the contributions, the balances at
 * retirement can differ materially.
 *
 * <p>The accumulation path here is the EXPECTED (deterministic) path; the trials' own market
 * dispersion before retirement is generated separately and models contributions, growth and fees
 * only (no taxes, conversions, RMDs or deaths). Pre-retirement events the deterministic engine also models (a
 * pre-retirement Roth conversion start year, an RMD forced on a still-working owner) are not
 * replayed here.
 */
final class PreRetirementAccumulation {

    private PreRetirementAccumulation() {
    }

    /**
     * Returns {@code input} unchanged when retirement starts in (or before) the base year, else a
     * copy whose accounts carry their projected start-of-retirement balance and cost basis.
     */
    static GuardrailOptimizationInput seedAtRetirement(GuardrailOptimizationInput input, RealReturnMatrix matrix) {
        int years = input.retirementDate().getYear() - input.baseYear();
        if (years <= 0) {
            return input;
        }
        Map<AssetClass, Double> geoMeans = CapitalMarketAssumptionsProvider.geometricMeansOf(matrix);
        BigDecimal inflationRate = input.inflationRate() != null ? input.inflationRate() : BigDecimal.ZERO;
        BigDecimal feeRate = BigDecimal.valueOf(OptimizationContextBuilder.resolveFeeRate(input));

        List<ProjectionAccountInput> seeded = new ArrayList<>(input.accounts().size());
        for (var account : input.accounts()) {
            BigDecimal growthFactor = BigDecimal.ONE.add(
                    PoolStrategy.realReturnFor(account, geoMeans, inflationRate, feeRate));
            BigDecimal contribution = account.annualContribution();
            BigDecimal balance = account.initialBalance();
            for (int y = 0; y < years; y++) {
                balance = balance.add(contribution).multiply(growthFactor)
                        .setScale(PoolStrategy.SCALE, PoolStrategy.ROUNDING);
            }
            BigDecimal basis = account.costBasis().add(contribution.multiply(BigDecimal.valueOf(years)));
            seeded.add(new HypotheticalAccountInput(balance, contribution, account.allocation(),
                    account.expectedReturnOverride(), basis, account.accountType(), account.owner()));
        }
        return input.withAccounts(seeded);
    }
}
