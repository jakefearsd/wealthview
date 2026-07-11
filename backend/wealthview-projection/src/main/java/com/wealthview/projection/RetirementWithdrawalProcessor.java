package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.strategy.WithdrawalContext;
import com.wealthview.core.projection.strategy.WithdrawalStrategy;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/**
 * Computes a single retirement year's portfolio withdrawal: resolving the spending
 * need (spending-plan or withdrawal-strategy driven), reinvesting after-tax surplus,
 * and executing the withdrawal across pools. Extracted from
 * {@link DeterministicProjectionEngine} to isolate retirement-withdrawal logic.
 */
final class RetirementWithdrawalProcessor {

    /** Bundles the per-year inputs needed by {@code process}. */
    record RetirementWithdrawalContext(
            PoolStrategy pool,
            WithdrawalStrategy strategy,
            @Nullable SpendingPlan spendingPlan,
            int age,
            int yearsInRetirement,
            int year,
            BigDecimal inflationRate,
            BigDecimal totalActiveIncome,
            BigDecimal startBalance,
            BigDecimal previousWithdrawal,
            BigDecimal effectiveOtherIncome,
            BigDecimal conversionAmount,
            @Nullable IncomeSourceProcessor.IncomeSourceYearResult isResult,
            @Nullable TaxCalculationStrategy taxStrategy,
            BigDecimal rmdAmount) {
    }

    /**
     * {@code ltcgTax} is the long-term capital-gains slice of {@code taxLiability} (see
     * {@link PoolStrategy.WithdrawalTaxResult#ltcgTax()}), threaded through so the engine can fold
     * it into the year's federal-tax breakdown -- see {@link RetirementTaxAnnotator}.
     */
    record RetirementWithdrawalResult(
            BigDecimal withdrawals,
            BigDecimal taxLiability,
            BigDecimal previousWithdrawal,
            BigDecimal surplusReinvested,
            BigDecimal withdrawalFromTaxable,
            BigDecimal withdrawalFromTraditional,
            BigDecimal withdrawalFromRoth,
            PoolStrategy.TaxSourceResult withdrawalTaxSource,
            BigDecimal ltcgTax,
            BigDecimal realizedLtcgIncome) {
    }

    RetirementWithdrawalResult process(RetirementWithdrawalContext rwCtx) {
        var pool = rwCtx.pool();
        var strategy = rwCtx.strategy();
        var spendingPlan = rwCtx.spendingPlan();
        int age = rwCtx.age();
        int yearsInRetirement = rwCtx.yearsInRetirement();
        int year = rwCtx.year();
        var inflationRate = rwCtx.inflationRate();
        var totalActiveIncome = rwCtx.totalActiveIncome();
        var startBalance = rwCtx.startBalance();
        var previousWithdrawal = rwCtx.previousWithdrawal();
        var effectiveOtherIncome = rwCtx.effectiveOtherIncome();
        var conversionAmount = rwCtx.conversionAmount();
        var isResult = rwCtx.isResult();
        var taxStrategy = rwCtx.taxStrategy();

        BigDecimal aggBalance = pool.getTotal();
        BigDecimal portfolioNeed;
        BigDecimal surplusReinvested = null;

        // Self-employment tax has no tax bundle of its own anywhere else this year -- it always
        // needs either the year's cash surplus or an explicit pool-cascade draw to actually leave
        // the portfolio (audit A4: it used to be added to the reported taxLiability with ZERO pool
        // effect in deficit years). Default to "fully unfunded"; the surplus branch below reduces
        // this when it can cover some or all of it from the year's cash surplus.
        BigDecimal seTax = (pool.tracksSETax() && isResult != null)
                ? isResult.selfEmploymentTax() : BigDecimal.ZERO;
        // Explicit signal threaded into executeWithdrawals -- see PoolStrategy#executeWithdrawals
        // javadoc. Zero unless the surplus branch below actually charges base-income tax this year.
        BigDecimal alreadyChargedBaseTax = BigDecimal.ZERO;
        // Tax this method has computed but could not fund from the year's cash surplus; must be
        // pulled from the pools via the same cascade that funds the withdrawal-tax bundle (audit
        // A4: this used to vanish at the old `.max(ZERO)` floor on the surplus deposit).
        BigDecimal extraPoolFundedTax = seTax;

        if (spendingPlan != null) {
            var resolved = spendingPlan.resolveYear(year, age, yearsInRetirement,
                    inflationRate, totalActiveIncome);
            portfolioNeed = resolved.portfolioWithdrawal().min(aggBalance);
            previousWithdrawal = resolved.totalSpending();

            // Detect surplus or exact-match: income meets or exceeds total spending.
            // Tax must be computed even when income exactly equals spending (zero surplus).
            BigDecimal grossSurplus = totalActiveIncome.subtract(resolved.totalSpending());
            if (grossSurplus.compareTo(BigDecimal.ZERO) >= 0) {
                BigDecimal tax = BigDecimal.ZERO;
                if (taxStrategy != null) {
                    BigDecimal surplusTaxableIncome = effectiveOtherIncome.add(conversionAmount);
                    FilingStatus filingStatus = pool.getFilingStatus();
                    BigDecimal fullTax = taxStrategy.computeTotalTax(surplusTaxableIncome, year, filingStatus);

                    if (conversionAmount.compareTo(BigDecimal.ZERO) > 0) {
                        // Roth conversion tax was already computed on (conversionAmount + effectiveOtherIncome).
                        // Only add the marginal tax not yet accounted for to avoid double-counting.
                        BigDecimal baseTax = taxStrategy.computeTotalTax(
                                conversionAmount.add(effectiveOtherIncome), year, filingStatus);
                        tax = fullTax.subtract(baseTax).max(BigDecimal.ZERO);
                    } else {
                        tax = fullTax;
                    }
                }
                alreadyChargedBaseTax = tax;

                // A4: fund (tax + SE tax) from the surplus first; route any unfunded remainder
                // through the pool cascade (extraPoolFundedTax, threaded into executeWithdrawals
                // below) instead of letting it vanish at a `.max(ZERO)` floor.
                BigDecimal totalObligation = tax.add(seTax);
                BigDecimal afterTaxSurplus = grossSurplus.subtract(totalObligation);
                if (afterTaxSurplus.compareTo(BigDecimal.ZERO) > 0) {
                    pool.depositToTaxable(afterTaxSurplus);
                    surplusReinvested = afterTaxSurplus;
                    extraPoolFundedTax = BigDecimal.ZERO;
                } else {
                    extraPoolFundedTax = afterTaxSurplus.negate();
                }
            }
        } else {
            var ctx = new WithdrawalContext(
                    aggBalance, startBalance, previousWithdrawal, pool.getWeightedReturn(),
                    inflationRate, yearsInRetirement);
            portfolioNeed = strategy.computeWithdrawal(ctx).min(aggBalance);
            previousWithdrawal = portfolioNeed;
        }

        var withdrawalResult = pool.executeWithdrawals(
                portfolioNeed, year, effectiveOtherIncome, conversionAmount, rwCtx.rmdAmount(), age,
                alreadyChargedBaseTax, extraPoolFundedTax);

        // withdrawalResult.taxLiability() already includes extraPoolFundedTax (now pool-funded).
        // Add back exactly the portion funded from this year's cash surplus instead (zero in
        // deficit/no-plan years, where extraPoolFundedTax already equals the full obligation) so
        // (tax + seTax) is reported exactly once, regardless of how it was funded.
        BigDecimal fundedFromSurplus = alreadyChargedBaseTax.add(seTax).subtract(extraPoolFundedTax);
        BigDecimal taxLiability = withdrawalResult.taxLiability().add(fundedFromSurplus);

        return new RetirementWithdrawalResult(withdrawalResult.totalWithdrawn(), taxLiability,
                previousWithdrawal, surplusReinvested,
                withdrawalResult.fromTaxable(), withdrawalResult.fromTraditional(),
                withdrawalResult.fromRoth(), withdrawalResult.taxSource(), withdrawalResult.ltcgTax(),
                withdrawalResult.realizedLtcgIncome());
    }
}
