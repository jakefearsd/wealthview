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

    /**
     * Bundles the per-year inputs needed by {@code process}. {@code irmaaSurcharge} is the year's
     * ALREADY-COMPUTED Medicare IRMAA premium surcharge (Wave-4 IRMAA item) -- keyed on the
     * 2-years-prior MAGI by the caller ({@code DeterministicProjectionEngine}), so it is a stable,
     * known-in-advance dollar figure by the time this method runs (no fixed-point circularity like
     * the audit-B2 Social Security convergence). Zero when not applicable (not retired, under
     * Medicare age, or below the first IRMAA threshold). It is NOT a tax -- it is added directly to
     * the year's portfolio draw ({@code portfolioNeed}), exactly like essential/discretionary
     * spending, so it flows through the SAME withdrawal-order cascade and is funded from whichever
     * pool the scenario's withdrawal order draws from, WITHOUT going through the
     * {@code extraPoolFundedTax}/surplus-netting machinery {@code seTax} uses (that machinery folds
     * its amount into the reported {@code taxLiability}, which the surcharge must stay out of).
     */
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
            BigDecimal rmdAmount,
            BigDecimal irmaaSurcharge) {
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
        // The year's converged federally-taxable Social Security amount (audit B2), threaded into
        // EVERY tax bundle this method charges or triggers so states that exempt Social Security
        // don't tax it (audit C3). Consistency matters: the surplus-branch base tax below and
        // executeWithdrawals' full bundle must subtract the SAME amount, or the marginal netting
        // there loses its full>=base monotonicity and the .max(ZERO) floor silently over-charges.
        BigDecimal ssTaxable = isResult != null ? isResult.socialSecurityTaxable() : BigDecimal.ZERO;
        // Wave-4 IRMAA item: this year's Medicare premium surcharge, already computed by the caller
        // from the 2-years-prior MAGI -- see the RetirementWithdrawalContext javadoc for why it is
        // added to portfolioNeed below instead of extraPoolFundedTax.
        BigDecimal irmaaSurcharge = rwCtx.irmaaSurcharge() != null ? rwCtx.irmaaSurcharge() : BigDecimal.ZERO;
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
                    // 5-arg detailed form (audit C3): exempt the federally-taxed SS amount from the
                    // state base; no LTCG income here (that belongs to executeWithdrawals' bundle).
                    BigDecimal fullTax = taxStrategy.computeDetailedTax(surplusTaxableIncome, year,
                            filingStatus, BigDecimal.ZERO, ssTaxable).totalTax();

                    if (conversionAmount.compareTo(BigDecimal.ZERO) > 0) {
                        // Roth conversion tax was already computed on (conversionAmount + effectiveOtherIncome).
                        // Only add the marginal tax not yet accounted for to avoid double-counting.
                        BigDecimal baseTax = taxStrategy.computeDetailedTax(
                                conversionAmount.add(effectiveOtherIncome), year, filingStatus,
                                BigDecimal.ZERO, ssTaxable).totalTax();
                        tax = fullTax.subtract(baseTax).max(BigDecimal.ZERO);
                    } else {
                        tax = fullTax;
                    }
                }
                alreadyChargedBaseTax = tax;

                // A4: fund (tax + SE tax) from the surplus first; route any unfunded remainder
                // through the pool cascade (extraPoolFundedTax, threaded into executeWithdrawals
                // below) instead of letting it vanish at a `.max(ZERO)` floor. The IRMAA surcharge
                // is NOT part of this obligation -- it is added to portfolioNeed below instead.
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

        // Wave-4 IRMAA: fold the surcharge into the actual dollar draw, AFTER previousWithdrawal is
        // captured above (the surcharge is a one-off Medicare cost, not part of the ongoing spending
        // trajectory that previousWithdrawal feeds into next year's withdrawal-rate/smoothing
        // baseline) -- it flows through the SAME taxable/traditional/roth withdrawal-order cascade
        // as portfolioNeed, so a traditional-funded portion is taxed normally (as any other
        // distribution would be), while the surcharge dollar figure itself is reported separately
        // (not folded into taxLiability) via the DTO's irmaa_surcharge field.
        BigDecimal totalDraw = portfolioNeed.add(irmaaSurcharge).min(aggBalance);

        var withdrawalResult = pool.executeWithdrawals(
                totalDraw, year, effectiveOtherIncome, conversionAmount, rwCtx.rmdAmount(), age,
                alreadyChargedBaseTax, extraPoolFundedTax, ssTaxable);

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
