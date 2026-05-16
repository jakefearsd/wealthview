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
            @Nullable TaxCalculationStrategy taxStrategy) {
    }

    record RetirementWithdrawalResult(
            BigDecimal withdrawals,
            BigDecimal taxLiability,
            BigDecimal previousWithdrawal,
            BigDecimal surplusReinvested,
            BigDecimal withdrawalFromTaxable,
            BigDecimal withdrawalFromTraditional,
            BigDecimal withdrawalFromRoth,
            PoolStrategy.TaxSourceResult withdrawalTaxSource) {
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
        BigDecimal surplusTax = BigDecimal.ZERO;

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
                    FilingStatus filingStatus = FilingStatus.fromString(pool.getFilingStatusString());
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
                // Also subtract self-employment tax from the surplus deposit
                BigDecimal seTax = (pool.tracksSETax() && isResult != null)
                        ? isResult.selfEmploymentTax() : BigDecimal.ZERO;
                surplusTax = tax;
                BigDecimal afterTaxSurplus = grossSurplus.subtract(tax).subtract(seTax).max(BigDecimal.ZERO);
                if (afterTaxSurplus.compareTo(BigDecimal.ZERO) > 0) {
                    pool.depositToTaxable(afterTaxSurplus);
                    surplusReinvested = afterTaxSurplus;
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
                portfolioNeed, year, effectiveOtherIncome, conversionAmount, BigDecimal.ZERO, age);
        BigDecimal taxLiability = withdrawalResult.taxLiability().add(surplusTax);

        if (pool.tracksSETax() && isResult != null
                && isResult.selfEmploymentTax().compareTo(BigDecimal.ZERO) > 0) {
            taxLiability = taxLiability.add(isResult.selfEmploymentTax());
        }

        return new RetirementWithdrawalResult(withdrawalResult.totalWithdrawn(), taxLiability,
                previousWithdrawal, surplusReinvested,
                withdrawalResult.fromTaxable(), withdrawalResult.fromTraditional(),
                withdrawalResult.fromRoth(), withdrawalResult.taxSource());
    }
}
