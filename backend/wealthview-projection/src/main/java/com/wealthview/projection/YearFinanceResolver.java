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

    /**
     * Immutable per-year inputs consumed by {@link #resolve}. {@code irmaaSurcharge} is the year's
     * already-computed Medicare IRMAA premium surcharge (Wave-4 IRMAA item, zero when not
     * applicable) -- passed straight through to {@link RetirementWithdrawalProcessor}; see that
     * class's javadoc for why it is not part of the Social Security convergence loop.
     */
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
            BigDecimal rmdForced,
            BigDecimal irmaaSurcharge) {
    }

    /**
     * Everything a resolved projection year yields, plus the realized ordinary income for
     * convergence. {@code realizedLtcgIncome} and {@code socialSecurityTaxable} are broken out
     * separately (rather than only folded into {@code realizedPortfolioTaxable}) so callers can
     * thread them into the STATE tax base (audit C3: {@code CombinedTaxCalculator}'s LTCG /
     * Social-Security-exemption seam) without re-deriving them from the SS convergence arithmetic.
     *
     * <p>{@code ordinaryInterestIncome} (audit C1) is the year's ordinary-interest income (the
     * taxable pool's bond+cash sleeve) -- unlike {@code realizedLtcgIncome} it is ORDINARY, not
     * LTCG, income: it is already folded into {@code taxLiability}'s bundle and into {@code
     * realizedPortfolioTaxable} below (the Social Security provisional-income base), and is broken
     * out separately so {@link DeterministicProjectionEngine} can also fold it into {@link
     * RetirementTaxAnnotator}'s displayed federal/state recompute the same way.
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
            BigDecimal socialSecurityTaxable,
            BigDecimal earlyWithdrawalPenalty,
            BigDecimal ordinaryInterestIncome) {
    }

    YearComputation resolve(YearContext yc) {
        var pool = yc.pool();
        // T18a-1 + household task 4: IRS ordering -- the year's RMD must be distributed BEFORE any
        // Roth conversion. The physical force-out (one stream per owner) has ALREADY happened in
        // DeterministicProjectionEngine#computeAndForceRmdStreams, right after growth and before this
        // method's Social Security convergence snapshot below, so every convergence pass restores to
        // a pool state that already reflects the distribution (the physical RMD is a one-time event,
        // not itself part of the circular SS fixed point). yc.rmdForced() is the summed amount
        // actually distributed across the streams.
        BigDecimal rmdForced = yc.rmdForced();

        boolean converge = hasActiveSocialSecurity(yc.incomeSources(), yc.age());
        PoolStrategy.Memento snapshot = converge ? pool.snapshot() : null;

        BigDecimal additionalProvisional = BigDecimal.ZERO;
        var comp = computeIncomeConversionWithdrawal(yc, additionalProvisional, rmdForced);

        if (converge) {
            int iterations = 1;
            while (comp.realizedPortfolioTaxable().subtract(additionalProvisional).abs()
                            .compareTo(SS_CONVERGENCE_TOLERANCE) >= 0
                    && iterations < MAX_SS_CONVERGENCE_ITERATIONS) {
                iterations++;
                additionalProvisional = comp.realizedPortfolioTaxable();
                pool.restore(snapshot);
                comp = computeIncomeConversionWithdrawal(yc, additionalProvisional, rmdForced);
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
                                                             BigDecimal additionalProvisionalIncome,
                                                             BigDecimal rmdForced) {
        var pool = yc.pool();
        var incomeResult = processIncomeAndConversions(yc, additionalProvisionalIncome, rmdForced);
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
        BigDecimal earlyWithdrawalPenalty = BigDecimal.ZERO;
        BigDecimal ordinaryInterestIncome = BigDecimal.ZERO;

        // The converged federally-taxable Social Security amount for this year (audit B2's fixed
        // point has already run by the time this method returns for the final pass) -- zero when no
        // income sources were processed this year (pre-retirement years that skip income-source
        // processing entirely; see processIncomeAndConversions).
        BigDecimal socialSecurityTaxable = incomeResult.isResult() != null
                ? incomeResult.isResult().socialSecurityTaxable() : BigDecimal.ZERO;

        // T18a-3: this year's aggregate net taxable rental income, threaded into the LTCG/NIIT
        // bundle's Net Investment Income base (see PoolStrategy#executeWithdrawals's 10-arg
        // overload) -- zero when no rental income sources are active.
        BigDecimal netRentalIncome = incomeResult.isResult() != null
                ? incomeResult.isResult().netRentalTaxableIncome() : BigDecimal.ZERO;

        if (yc.retired()) {
            var rwCtx = new RetirementWithdrawalProcessor.RetirementWithdrawalContext(
                    pool, yc.strategy(), yc.spendingPlan(), yc.age(), yc.yearsInRetirement(), yc.year(),
                    yc.inflationRate(), incomeResult.totalActiveIncome(), yc.startBalance(),
                    previousWithdrawal, incomeResult.effectiveOtherIncome(), conversionAmount,
                    incomeResult.isResult(), yc.taxStrategy(), rmdForced, yc.irmaaSurcharge());
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
            earlyWithdrawalPenalty = retirementResult.earlyWithdrawalPenalty();
            ordinaryInterestIncome = retirementResult.ordinaryInterestIncome();
        } else if (rmdForced.compareTo(BigDecimal.ZERO) > 0) {
            // T18a-2: RMDs apply from the SECURE-2.0 age regardless of retirement status (a
            // still-working owner still owes tax on a forced traditional distribution) -- but a
            // not-yet-retired year has no spend-draw need and none of RetirementWithdrawalProcessor's
            // spending-plan/withdrawal-strategy machinery applies. Run executeWithdrawals directly
            // with a zero spend need so ONLY the forced RMD (and any taxable-pool dividend/LTCG) is
            // taxed -- mirrors the retired, fully-income-covered case (audit A2) that already taxes
            // a forced RMD past a zero portfolio need.
            var withdrawalResult = pool.executeWithdrawals(BigDecimal.ZERO, yc.year(),
                    incomeResult.effectiveOtherIncome(), conversionAmount, rmdForced, yc.age(),
                    BigDecimal.ZERO, BigDecimal.ZERO, socialSecurityTaxable, netRentalIncome);
            taxLiability = taxLiability.add(withdrawalResult.taxLiability());
            wdFromTaxable = withdrawalResult.fromTaxable();
            wdFromTraditional = withdrawalResult.fromTraditional();
            wdFromRoth = withdrawalResult.fromRoth();
            withdrawalTaxSource = withdrawalResult.taxSource();
            ltcgTax = withdrawalResult.ltcgTax();
            realizedLtcgIncome = withdrawalResult.realizedLtcgIncome();
            earlyWithdrawalPenalty = withdrawalResult.earlyWithdrawalPenalty();
            ordinaryInterestIncome = withdrawalResult.ordinaryInterestIncome();
        }

        var combinedTaxSource = incomeResult.conversionTaxSource().add(withdrawalTaxSource);

        // Realized ordinary income for the Social Security provisional-income fixed point: taxable
        // traditional distributions (spend draw + RMD force-out) + Roth conversion + realized
        // LTCG/dividend income + (audit C1) realized ordinary-interest income. Matches the IRS
        // worksheet's AGI-ex-SS additions -- interest is ordinary AGI same as a traditional
        // distribution, so it belongs in this base exactly like realizedLtcgIncome already does.
        BigDecimal realizedPortfolioTaxable = wdFromTraditional.add(conversionAmount)
                .add(realizedLtcgIncome).add(ordinaryInterestIncome);

        return new YearComputation(incomeResult.isResult(), incomeResult.totalActiveIncome(),
                incomeResult.effectiveOtherIncome(), conversionAmount, taxLiability, suspendedLoss,
                withdrawals, previousWithdrawal, surplusReinvested, wdFromTaxable, wdFromTraditional,
                wdFromRoth, ltcgTax, combinedTaxSource, realizedPortfolioTaxable, realizedLtcgIncome,
                socialSecurityTaxable, earlyWithdrawalPenalty, ordinaryInterestIncome);
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
                                                                 BigDecimal additionalProvisionalIncome,
                                                                 BigDecimal rmdForced) {
        var pool = yc.pool();
        int age = yc.age();
        int yearsInRetirement = yc.yearsInRetirement();
        int year = yc.year();
        BigDecimal inflationRate = yc.inflationRate();
        // Audit C7: income deflates on the CALENDAR clock (years elapsed since the projection's
        // base year), not the retirement-anchored yearsInRetirement -- a fixed-nominal source held
        // full face value through accumulation (yearsInRetirement pinned at 0) regardless of how
        // many calendar years had actually passed. 1-indexed (IncomeYearMath.realAmount's shape):
        // the base year itself is 1. Floored at 1 so a year at-or-before the base year never goes
        // negative, mirroring the Social Security threshold deflator's own
        // Math.max(0, taxYear - baseYear) floor.
        int yearsFromBase = Math.max(0, year - yc.baseYear()) + 1;

        IncomeSourceProcessor.IncomeSourceYearResult incomeSourceResult = null;
        BigDecimal totalActiveIncome;
        BigDecimal taxableActiveIncome;

        if (pool.processIncomeSourcesEveryYear() || yearsInRetirement > 0) {
            incomeSourceResult = incomeSourceProcessor.process(yc.incomeSources(), age, yearsFromBase,
                    year, pool.getMagi(), pool.getFilingStatus(), yc.suspendedLoss(), inflationRate,
                    yc.baseYear(), additionalProvisionalIncome);
            totalActiveIncome = incomeSourceResult.totalCashInflow();
            taxableActiveIncome = incomeSourceResult.totalTaxableIncome();
        } else {
            totalActiveIncome = incomeContributionCalculator.compute(
                    yc.incomeSources(), age, yearsFromBase, inflationRate);
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

        // T18a-1: rmdForced (not yc.rmdAmount()) — the RMD has ALREADY been forced out of
        // traditional (see YearFinanceResolver#resolve) by the time conversion runs; the bracket-
        // headroom subtraction below uses the amount actually realized, not the pre-force estimate.
        PoolStrategy.ConversionResult conversion;
        if (conversionOverride != null && conversionOverride.compareTo(BigDecimal.ZERO) > 0) {
            conversion = pool.executeRothConversionOverride(
                    year, effectiveOtherIncome, conversionOverride, rmdForced, ssTaxable);
        } else if (conversionOverride != null) {
            // Override is present but zero → no conversion this year
            conversion = new PoolStrategy.ConversionResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, PoolStrategy.TaxSourceResult.ZERO);
        } else {
            conversion = pool.executeRothConversion(year, effectiveOtherIncome, rmdForced, ssTaxable);
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
