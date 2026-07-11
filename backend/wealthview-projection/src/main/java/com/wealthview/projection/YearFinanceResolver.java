package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.strategy.WithdrawalStrategy;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/**
 * Resolves a single projection year's income, Roth conversion, and retirement withdrawal — the
 * pool-mutating core of a year — with the audit-B2 Social Security provisional-income convergence.
 * Extracted from {@link DeterministicProjectionEngine} to isolate the income/conversion/withdrawal
 * orchestration (and its fixed-point loop) from the engine's parameter parsing and DTO assembly.
 *
 * <p>Taxable Social Security depends on the year's realized ORDINARY income (traditional withdrawals
 * + RMD excess + the audit-C2 tax gross-up draw + Roth conversion + realized LTCG/dividends), but
 * those are only known AFTER the conversion/withdrawal steps run, and they in turn can depend
 * (under bracket-fill conversion, dynamic-sequencing withdrawal, and the C2 gross-up -- whose
 * slice grows with the tax bill, which grows with taxable Social Security) on how much Social
 * Security is taxable. {@link #resolve} breaks this circular dependency with a fixed point: it
 * snapshots the post-growth pool, runs the year with an estimate of the realized ordinary income
 * folded into the Social Security provisional base, then re-runs from the snapshot with the actual
 * realized figure until the two agree within {@link #SS_CONVERGENCE_TOLERANCE} or after
 * {@link #MAX_SS_CONVERGENCE_ITERATIONS} passes. Taxable Social Security is monotone
 * piecewise-linear in ordinary income and capped at 85%, and the withdrawal-feedback map is a
 * contraction (|slope| &le; 0.85; the composed C2 leg adds only a further marginal-rate-scaled
 * slope, keeping the product below 1), so it converges quickly. Since audit C2, though, the
 * realized ordinary income is NOT independent of Social Security taxability even in the plain
 * ordered-withdrawal case (a larger taxable-SS figure raises the tax bill, which raises the
 * traditional gross-up draw), so the pre-C2 "exact fixed point in a single re-run" property no
 * longer holds in general -- termination is guaranteed by the hard iteration cap, with the
 * contraction keeping the residual small. When no Social Security source is active the loop is
 * skipped, so behavior is byte-identical to a single pass with zero extra provisional income.
 */
final class YearFinanceResolver {

    /** Maximum fixed-point passes over the year's Social Security provisional-income convergence. */
    private static final int MAX_SS_CONVERGENCE_ITERATIONS = 4;
    /** Convergence tolerance (dollars) for the Social Security provisional-income fixed point. */
    private static final BigDecimal SS_CONVERGENCE_TOLERANCE = BigDecimal.ONE;

    private final IncomeSourceProcessor incomeSourceProcessor;
    private final IncomeContributionCalculator incomeContributionCalculator;
    private final RetirementWithdrawalProcessor retirementWithdrawalProcessor;

    YearFinanceResolver(IncomeSourceProcessor incomeSourceProcessor,
                        IncomeContributionCalculator incomeContributionCalculator,
                        RetirementWithdrawalProcessor retirementWithdrawalProcessor) {
        this.incomeSourceProcessor = incomeSourceProcessor;
        this.incomeContributionCalculator = incomeContributionCalculator;
        this.retirementWithdrawalProcessor = retirementWithdrawalProcessor;
    }

    /** Immutable per-year inputs consumed by {@link #resolve}. */
    record YearContext(
            PoolStrategy pool,
            List<ProjectionIncomeSourceInput> incomeSources,
            @Nullable SpendingPlan spendingPlan,
            WithdrawalStrategy strategy,
            @Nullable TaxCalculationStrategy taxStrategy,
            BigDecimal inflationRate,
            int year,
            int age,
            boolean retired,
            int yearsInRetirement,
            BigDecimal startBalance,
            int baseYear,
            BigDecimal previousWithdrawal,
            BigDecimal suspendedLoss,
            BigDecimal rmdAmount) {
    }

    /**
     * Everything a resolved projection year yields, plus the realized ordinary income for
     * convergence. {@code realizedLtcgIncome} and {@code socialSecurityTaxable} are broken out
     * separately (rather than only folded into {@code realizedPortfolioTaxable}) so callers can
     * thread them into the STATE tax base (audit C3: {@code CombinedTaxCalculator}'s LTCG /
     * Social-Security-exemption seam) without re-deriving them from the SS convergence arithmetic.
     */
    record YearComputation(
            IncomeSourceProcessor.IncomeSourceYearResult isResult,
            BigDecimal totalActiveIncome,
            BigDecimal effectiveOtherIncome,
            BigDecimal conversionAmount,
            BigDecimal taxLiability,
            BigDecimal suspendedLoss,
            BigDecimal withdrawals,
            BigDecimal previousWithdrawal,
            BigDecimal surplusReinvested,
            BigDecimal wdFromTaxable,
            BigDecimal wdFromTraditional,
            BigDecimal wdFromRoth,
            BigDecimal ltcgTax,
            PoolStrategy.TaxSourceResult combinedTaxSource,
            BigDecimal realizedPortfolioTaxable,
            BigDecimal realizedLtcgIncome,
            BigDecimal socialSecurityTaxable) {
    }

    YearComputation resolve(YearContext yc) {
        var pool = yc.pool();
        boolean converge = hasActiveSocialSecurity(yc.incomeSources(), yc.age());
        PoolStrategy.Memento snapshot = converge ? pool.snapshot() : null;

        BigDecimal additionalProvisional = BigDecimal.ZERO;
        var comp = computeIncomeConversionWithdrawal(yc, additionalProvisional);

        if (converge) {
            int iterations = 1;
            while (comp.realizedPortfolioTaxable().subtract(additionalProvisional).abs()
                            .compareTo(SS_CONVERGENCE_TOLERANCE) >= 0
                    && iterations < MAX_SS_CONVERGENCE_ITERATIONS) {
                iterations++;
                additionalProvisional = comp.realizedPortfolioTaxable();
                pool.restore(snapshot);
                comp = computeIncomeConversionWithdrawal(yc, additionalProvisional);
            }
        }
        return comp;
    }

    private boolean hasActiveSocialSecurity(List<ProjectionIncomeSourceInput> incomeSources, int age) {
        for (var source : incomeSources) {
            if (source.incomeType() == IncomeSourceType.SOCIAL_SECURITY
                    && ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                return true;
            }
        }
        return false;
    }

    private YearComputation computeIncomeConversionWithdrawal(YearContext yc,
                                                             BigDecimal additionalProvisionalIncome) {
        var pool = yc.pool();
        var incomeResult = processIncomeAndConversions(yc, additionalProvisionalIncome);
        BigDecimal suspendedLoss = incomeResult.suspendedLoss();
        BigDecimal conversionAmount = incomeResult.conversionAmount();
        BigDecimal taxLiability = incomeResult.taxLiability();

        BigDecimal withdrawals = BigDecimal.ZERO;
        BigDecimal surplusReinvested = null;
        BigDecimal wdFromTaxable = BigDecimal.ZERO;
        BigDecimal wdFromTraditional = BigDecimal.ZERO;
        BigDecimal wdFromRoth = BigDecimal.ZERO;
        BigDecimal previousWithdrawal = yc.previousWithdrawal();
        PoolStrategy.TaxSourceResult withdrawalTaxSource = PoolStrategy.TaxSourceResult.ZERO;
        BigDecimal ltcgTax = BigDecimal.ZERO;
        BigDecimal realizedLtcgIncome = BigDecimal.ZERO;
        if (yc.retired()) {
            var rwCtx = new RetirementWithdrawalProcessor.RetirementWithdrawalContext(
                    pool, yc.strategy(), yc.spendingPlan(), yc.age(), yc.yearsInRetirement(), yc.year(),
                    yc.inflationRate(), incomeResult.totalActiveIncome(), yc.startBalance(),
                    previousWithdrawal, incomeResult.effectiveOtherIncome(), conversionAmount,
                    incomeResult.isResult(), yc.taxStrategy(), yc.rmdAmount());
            var retirementResult = retirementWithdrawalProcessor.process(rwCtx);
            withdrawals = retirementResult.withdrawals();
            taxLiability = taxLiability.add(retirementResult.taxLiability());
            previousWithdrawal = retirementResult.previousWithdrawal();
            surplusReinvested = retirementResult.surplusReinvested();
            wdFromTaxable = retirementResult.withdrawalFromTaxable();
            wdFromTraditional = retirementResult.withdrawalFromTraditional();
            wdFromRoth = retirementResult.withdrawalFromRoth();
            withdrawalTaxSource = retirementResult.withdrawalTaxSource();
            ltcgTax = retirementResult.ltcgTax();
            realizedLtcgIncome = retirementResult.realizedLtcgIncome();
        }

        var combinedTaxSource = incomeResult.conversionTaxSource().add(withdrawalTaxSource);

        // Realized ordinary income for the Social Security provisional-income fixed point: taxable
        // traditional distributions (spend draw + RMD force-out) + Roth conversion + realized
        // LTCG/dividend income. Matches the IRS worksheet's AGI-ex-SS additions.
        BigDecimal realizedPortfolioTaxable = wdFromTraditional.add(conversionAmount).add(realizedLtcgIncome);

        // The converged federally-taxable Social Security amount for this year (audit B2's fixed
        // point has already run by the time this method returns for the final pass) -- zero when no
        // income sources were processed this year (pre-retirement years that skip income-source
        // processing entirely; see processIncomeAndConversions).
        BigDecimal socialSecurityTaxable = incomeResult.isResult() != null
                ? incomeResult.isResult().socialSecurityTaxable() : BigDecimal.ZERO;

        return new YearComputation(incomeResult.isResult(), incomeResult.totalActiveIncome(),
                incomeResult.effectiveOtherIncome(), conversionAmount, taxLiability, suspendedLoss,
                withdrawals, previousWithdrawal, surplusReinvested, wdFromTaxable, wdFromTraditional,
                wdFromRoth, ltcgTax, combinedTaxSource, realizedPortfolioTaxable, realizedLtcgIncome,
                socialSecurityTaxable);
    }

    private record IncomeAndConversionResult(
            IncomeSourceProcessor.IncomeSourceYearResult isResult,
            BigDecimal totalActiveIncome,
            BigDecimal effectiveOtherIncome,
            BigDecimal conversionAmount,
            BigDecimal taxLiability,
            BigDecimal suspendedLoss,
            PoolStrategy.TaxSourceResult conversionTaxSource) {
    }

    private IncomeAndConversionResult processIncomeAndConversions(YearContext yc,
                                                                 BigDecimal additionalProvisionalIncome) {
        var pool = yc.pool();
        int age = yc.age();
        int yearsInRetirement = yc.yearsInRetirement();
        int year = yc.year();
        BigDecimal inflationRate = yc.inflationRate();

        IncomeSourceProcessor.IncomeSourceYearResult incomeSourceResult = null;
        BigDecimal totalActiveIncome;
        BigDecimal taxableActiveIncome;

        if (pool.processIncomeSourcesEveryYear() || yearsInRetirement > 0) {
            incomeSourceResult = incomeSourceProcessor.process(yc.incomeSources(), age, yearsInRetirement,
                    year, pool.getMagi(), pool.getFilingStatus(), yc.suspendedLoss(), inflationRate,
                    yc.baseYear(), additionalProvisionalIncome);
            totalActiveIncome = incomeSourceResult.totalCashInflow();
            taxableActiveIncome = incomeSourceResult.totalTaxableIncome();
        } else {
            totalActiveIncome = incomeContributionCalculator.compute(
                    yc.incomeSources(), age, yearsInRetirement, inflationRate);
            taxableActiveIncome = totalActiveIncome;
        }

        BigDecimal suspendedLoss = incomeSourceResult != null
                ? incomeSourceResult.suspendedLossCarryforward() : yc.suspendedLoss();
        BigDecimal effectiveOtherIncome = pool.computeEffectiveOtherIncome(taxableActiveIncome, BigDecimal.ZERO);
        BigDecimal conversionOverride = resolveConversionOverride(yc.spendingPlan(), year);

        // This iteration's federally-taxable Social Security amount (already resolved above; the
        // audit-B2 fixed point re-runs this whole method until it is self-consistent), threaded into
        // the conversion-tax bundle's state-base computation (audit C3).
        BigDecimal ssTaxable = incomeSourceResult != null
                ? incomeSourceResult.socialSecurityTaxable() : BigDecimal.ZERO;

        PoolStrategy.ConversionResult conversion;
        if (conversionOverride != null && conversionOverride.compareTo(BigDecimal.ZERO) > 0) {
            conversion = pool.executeRothConversionOverride(
                    year, effectiveOtherIncome, conversionOverride, yc.rmdAmount(), ssTaxable);
        } else if (conversionOverride != null) {
            // Override is present but zero → no conversion this year
            conversion = new PoolStrategy.ConversionResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, PoolStrategy.TaxSourceResult.ZERO);
        } else {
            conversion = pool.executeRothConversion(year, effectiveOtherIncome, yc.rmdAmount(), ssTaxable);
        }

        return new IncomeAndConversionResult(incomeSourceResult, totalActiveIncome, effectiveOtherIncome,
                conversion.amountConverted(), conversion.taxLiability(), suspendedLoss, conversion.taxSource());
    }

    @Nullable
    private BigDecimal resolveConversionOverride(@Nullable SpendingPlan spendingPlan, int year) {
        if (spendingPlan == null) {
            return null;
        }
        return spendingPlan.conversionSchedule()
                .map(schedule -> schedule.getOrDefault(year, BigDecimal.ZERO))
                .orElse(null);
    }
}
