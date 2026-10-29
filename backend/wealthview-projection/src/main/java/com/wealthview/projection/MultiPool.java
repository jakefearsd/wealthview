package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wealthview.core.projection.dto.LotOwner;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

final class MultiPool implements PoolStrategy {
    /**
     * The taxable pool, tracked as FIFO cost-basis lots so withdrawals can realize long-term
     * capital gains. Replaces the pre-lots scalar {@code taxable} balance; its {@code totalValue()}
     * is the taxable balance everywhere the scalar was read.
     */
    private final TaxableLotsBd lots;
    /**
     * Household task 4: the traditional and Roth pools are now owner-keyed ({@link OwnerPool} over
     * {@link PersonId}) so an age-gap couple can run one RMD stream per owner and contributions
     * can route to the owning spouse's pool. Every public accessor and the tax cascade still
     * operate on the SUM across owners ({@link OwnerPool#total()}); a single-person scenario has
     * exactly one entry ({@link PersonId#PRIMARY}) so all arithmetic reduces to the pre-household
     * scalar path bit-for-bit. Within-type draws (spend draw, tax cascade) split proportionally by
     * owner balance — see {@link OwnerPool#debitProportional}. The joint taxable pool stays a
     * single {@link TaxableLotsBd} (joint accounts are taxable-only).
     */
    private final OwnerPool traditional;
    private final OwnerPool roth;
    private Optional<CombinedTaxResult> lastTaxBreakdown = Optional.empty();
    /**
     * The current year's qualified-dividend income (value × dividend yield), booked in
     * {@link #applyGrowth(boolean)} and consumed as LTCG income in {@link #executeWithdrawals}.
     * Reset each {@code applyGrowth} call. Audit C8: a RETIRED year is the only year that ever
     * books this at all -- see {@link #applyGrowth(boolean)}'s javadoc for why an accumulation
     * year no longer runs the distribution split (previously it booked a dividend every year but
     * only ever taxed/consumed it in a retired or RMD-forced year, which is exactly the untaxed
     * basis-step bug that item fixes).
     */
    private BigDecimal qualifiedDividendIncome = BigDecimal.ZERO;
    /**
     * Audit C1: the current year's ordinary-interest income (bond+cash share of the taxable
     * pool's yield, at {@link #interestYield}), booked in {@link #applyGrowth(boolean)} alongside
     * {@link #qualifiedDividendIncome} and consumed as ORDINARY income (not LTCG) in {@link
     * #executeWithdrawals} — it joins {@code taxableIncome} directly, the same bundle {@code
     * effectiveOtherIncome}/{@code conversionAmount}/the traditional distribution feed. Reset
     * each {@code applyGrowth} call; audit C8: zero for every accumulation year, exactly like
     * {@link #qualifiedDividendIncome} above.
     */
    private BigDecimal ordinaryInterestIncome = BigDecimal.ZERO;
    /** D5: FIFO gain realized this year by {@link #deductFromPools}'s taxable slice (conversion-tax
     * sales) that {@link #executeWithdrawals} has not yet folded into the year's LTCG income. May be
     * negative (loss lots). Captured by the memento so the Social Security fixed point restores it. */
    private BigDecimal pendingTaxSaleGain = BigDecimal.ZERO;

    /** Bond tent wiring (inert until {@link #configureTent}). */
    private final TentPools tents;

    private final OwnerPool tradContrib;
    private final OwnerPool rothContrib;
    /**
     * HP1: annual taxable contributions split by the contributing account's owner category
     * ({@code joint}/{@code primary}/{@code spouse}), so each year's contribution enters the
     * commingled lot set tagged with its owner (the first-death step-up then applies that owner's
     * exact statutory rate). Single-person scenarios put the whole contribution on
     * {@link #taxableContribPrimary} (accounts default to {@code primary}), so the tagged lot has
     * the same value as the pre-HP1 single aggregate contribution lot — byte-identical.
     */
    private final BigDecimal taxableContribJoint;
    private final BigDecimal taxableContribPrimary;
    private final BigDecimal taxableContribSpouse;
    private final BigDecimal weightedReturn;

    /**
     * Household task 5: no longer final — the first-death transition flips it to
     * {@link FilingStatus#SINGLE} from the transition year (see {@link #applyFirstDeathTransition}).
     * Deliberately NOT part of {@link MultiPoolMemento}: the Social Security convergence loop
     * restores pool balances but must leave the post-transition filing status in place, so the
     * flip is applied once and never re-applied or reverted across the fixed-point re-runs.
     */
    private FilingStatus filingStatus;
    private final BigDecimal otherIncome;
    private final BigDecimal annualRothConversion;
    private final String rothConversionStrategy;
    private final BigDecimal targetBracketRate;
    private final Integer rothConversionStartYear;
    private final WithdrawalOrder withdrawalOrder;
    private final TaxCalculationStrategy taxCalculator;
    private final BigDecimal dynamicSequencingBracketRate;

    private final BigDecimal dividendYield;
    /** Audit C1: nominal annual coupon proxy for the taxable pool's bond+cash sleeve. */
    private final BigDecimal interestYield;
    /**
     * Audit C1: the taxable pool's balance-weighted equity share (us_stock + intl_stock),
     * computed once at construction via {@link PoolReturnBlender#taxableEquityShare} — {@code 1.0}
     * (bond share {@code 0.0}) for every ALL_US or no-allocation account, the byte-identical
     * backward-compat anchor for {@link #applyGrowth(boolean)}'s yield split.
     */
    private final BigDecimal taxableEquityShare;
    private final int earlyAccessAge;
    /** The withdrawal cycle's tax-bill fixed-point solver, built once from this pool's {@link PoolConfig}. */
    private final WithdrawalTaxBillSolver billSolver;

    /**
     * Legacy uniform-return constructor: all three pools grow at the same {@code weightedReturn}.
     * Retained so existing direct-construction callers (and tests) keep their behavior.
     */
    MultiPool(Map<PoolType, List<ProjectionAccountInput>> grouped,
              BigDecimal weightedReturn,
              PoolConfig config) {
        this(grouped, weightedReturn, weightedReturn, weightedReturn, weightedReturn, config);
    }

    MultiPool(Map<PoolType, List<ProjectionAccountInput>> grouped,
              BigDecimal taxableReturn, BigDecimal traditionalReturn, BigDecimal rothReturn,
              BigDecimal weightedReturn,
              PoolConfig config) {
        // Seed one FIFO lot per taxable account: basis = its cost basis, value = its balance,
        // so any embedded (unrealized) gain is carried into the projection. HP1: each lot is
        // tagged with its source account's owner so the first-death step-up applies the EXACT
        // per-owner statutory rate (no more single blended factor over the commingled pool).
        this.lots = new TaxableLotsBd();
        var taxableAccounts = grouped.getOrDefault(PoolType.TAXABLE, List.of());
        for (var acct : taxableAccounts) {
            lots.addLot(acct.costBasis(), acct.initialBalance(), acct.ownerType());
        }
        // Audit C1: the taxable pool's own allocation-derived equity share, computed once from
        // the same account list the lots above were seeded from.
        this.taxableEquityShare = PoolReturnBlender.taxableEquityShare(taxableAccounts);
        this.traditional = OwnerPool.ofBalances(grouped.getOrDefault(PoolType.TRADITIONAL, List.of()));
        this.roth = OwnerPool.ofBalances(grouped.getOrDefault(PoolType.ROTH, List.of()));
        this.tents = new TentPools(lots, traditional, roth,
                new PoolReturns(taxableReturn, traditionalReturn, rothReturn));

        this.tradContrib = OwnerPool.ofContributions(grouped.getOrDefault(PoolType.TRADITIONAL, List.of()));
        this.rothContrib = OwnerPool.ofContributions(grouped.getOrDefault(PoolType.ROTH, List.of()));
        // HP1: taxable contributions split by owner so each year's contribution lot is tagged.
        this.taxableContribJoint = sumContribsByOwner(taxableAccounts, LotOwner.JOINT);
        this.taxableContribPrimary = sumContribsByOwner(taxableAccounts, LotOwner.PRIMARY);
        this.taxableContribSpouse = sumContribsByOwner(taxableAccounts, LotOwner.SPOUSE);

        this.weightedReturn = weightedReturn;
        this.filingStatus = config.filingStatus();
        this.otherIncome = config.otherIncome();
        this.annualRothConversion = config.annualRothConversion();
        this.rothConversionStrategy = config.rothConversionStrategy();
        this.targetBracketRate = config.targetBracketRate();
        this.rothConversionStartYear = config.rothConversionStartYear();
        this.withdrawalOrder = config.withdrawalOrder();
        this.taxCalculator = config.taxCalculator();
        this.dynamicSequencingBracketRate = config.dynamicSequencingBracketRate();
        this.dividendYield = config.dividendYield();
        this.interestYield = config.interestYield();
        this.earlyAccessAge = config.earlyAccessAge();
        this.billSolver = new WithdrawalTaxBillSolver(config.taxCalculator(), config.capitalGainsTaxCalculator(),
                config.federalTaxCalculator(), config.baseYear(), config.inflationRate(), config.earlyAccessAge());
    }

    /** HP1: annual taxable contribution summed over the accounts owned by one {@link LotOwner}
     * category, so each year's contribution enters the commingled lots tagged by its owner. */
    private static BigDecimal sumContribsByOwner(List<ProjectionAccountInput> accounts, LotOwner owner) {
        return accounts.stream()
                .filter(account -> account.ownerType() == owner)
                .map(ProjectionAccountInput::annualContribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public BigDecimal getTotal() {
        return lots.totalValue().add(traditional.total()).add(roth.total());
    }

    @Override
    public BigDecimal getTraditional() {
        return traditional.total();
    }

    @Override
    public Map<PersonId, BigDecimal> getTraditionalByOwner() {
        return traditional.byOwner();
    }

    /**
     * Number of open taxable lots -- a package-visible test-observability hook (audit C8) so a
     * unit test can confirm an accumulation-year {@link #applyGrowth(boolean)} call creates NO
     * new at-cost lot, without reaching into {@link #lots} via reflection.
     */
    int taxableLotCount() {
        return lots.lotCount();
    }

    @Override
    public BigDecimal getWeightedReturn() {
        return weightedReturn;
    }

    @Override
    public BigDecimal applyContributions() {
        tents.clamp();   // a prior debit into the sleeves must not be hidden by this credit
        // Household task 4: contributions route to the owner configured on each account (the
        // tradContrib/rothContrib schedules are keyed the same way traditional/roth are), so a
        // spouse-owned account grows the spouse's pool only.
        traditional.creditAll(tradContrib);
        roth.creditAll(rothContrib);
        // HP1: new contributions enter at cost (basis = value), each tagged by the contributing
        // account's owner. A zero amount is a no-op (addLot ignores it), so a single-person
        // scenario adds exactly one primary-tagged lot with the same value as the pre-HP1
        // aggregate contribution lot — byte-identical.
        lots.addLot(taxableContribJoint, taxableContribJoint, LotOwner.JOINT);
        lots.addLot(taxableContribPrimary, taxableContribPrimary, LotOwner.PRIMARY);
        lots.addLot(taxableContribSpouse, taxableContribSpouse, LotOwner.SPOUSE);
        BigDecimal taxableContrib = taxableContribJoint.add(taxableContribPrimary).add(taxableContribSpouse);
        return tradContrib.total().add(rothContrib.total()).add(taxableContrib);
    }

    @Override
    public GrowthResult applyGrowth(boolean retired) {
        tents.clamp();
        var returns = tents.returns();
        BigDecimal tradGrowth = tents.growOwnerPool(traditional, PoolType.TRADITIONAL, returns.traditional());
        BigDecimal rothGrowth = tents.growOwnerPool(roth, PoolType.ROTH, returns.roth());
        BigDecimal taxableGrowth = tents.growTaxable(retired, returns.taxable(), taxableEquityShare,
                        dividendYield, interestYield)
                .map(this::bookTaxableYear)
                .orElseGet(() -> growTaxableWithoutTent(retired));

        // OwnerPool.grow (above) already credited each owner's traditional/Roth slice with its
        // rounded growth; tradGrowth/rothGrowth are the summed totals for the GrowthResult.
        return new GrowthResult(tradGrowth.add(rothGrowth).add(taxableGrowth),
                taxableGrowth, tradGrowth, rothGrowth);
    }

    private BigDecimal growTaxableWithoutTent(boolean retired) {
        BigDecimal taxableBefore = lots.totalValue();
        BigDecimal taxableGrowth = taxableBefore.multiply(tents.returns().taxable()).setScale(SCALE, ROUNDING);
        BigDecimal targetTotal = taxableBefore.add(taxableGrowth);

        if (retired) {
            applyTaxableYieldSplit(targetTotal);
        } else {
            // Audit C8: an ACCUMULATION year books NO distribution at all -- lots appreciate at
            // the FULL total return r (pure, unrealized appreciation; basis untouched, no new
            // at-cost lot created). Pre-C8 the split below ran every year regardless of
            // retirement status, so an accumulation-year dividend/interest distribution was
            // reinvested as a fresh at-cost lot even though it is NEVER taxed pre-retirement (see
            // qualifiedDividendIncome/ordinaryInterestIncome's javadocs for the one narrow
            // exception this closes too: a still-working, RMD-forced year, whose taxable-pool
            // yield WAS taxed pre-C8 -- gating strictly on `retired` intentionally stops taxing
            // it, since that RMD-forced tax was itself only a side effect of the same
            // now-removed accumulation-year split, not a separate deliberate design point) -- a
            // real, untaxed basis step-up a taxed investor would never get for free. Treating the
            // whole return as unrealized appreciation instead simply defers the entire gain to
            // the eventual sale, which is what an untaxed reinvestment economically IS in this
            // model's constant-real frame. growToExactTotal keeps the reported total return
            // (taxableGrowth/targetTotal, and therefore the whole projection's BALANCE
            // trajectory) byte-identical to the pre-C8 split path's own total-preserving
            // guarantee -- only the lots' internal basis composition differs.
            lots.growToExactTotal(tents.returns().taxable(), targetTotal);
            qualifiedDividendIncome = BigDecimal.ZERO;
            ordinaryInterestIncome = BigDecimal.ZERO;
        }

        return taxableGrowth;
    }

    private BigDecimal bookTaxableYear(TentReservation.TaxableYear year) {
        qualifiedDividendIncome = year.dividend();
        ordinaryInterestIncome = year.interest();
        return year.growth();
    }

    @Override
    public TentPools tentPools() {
        return tents;
    }

    /**
     * Split the taxable return: existing lots appreciate at (r − blendedYield); the
     * distribution (≈ value × blendedYield) is reinvested as a fresh at-cost lot, booked as
     * the residual to the exact target total, so the taxable pool still grows at precisely
     * r regardless of the split (bit-identical to the pre-lots scalar path when both yields
     * are 0). Audit C1: the distribution itself is then split BETWEEN qualified-dividend
     * income (the equity share, taxableEquityShare, at dividendYield — TAXED as LTCG/
     * qualified-dividend income, unchanged from pre-C1) and ordinary-interest income (the
     * remaining bond+cash share, at interestYield — TAXED as ORDINARY income instead). Audit
     * C8: only ever invoked for a RETIRED year (see {@link #applyGrowth(boolean)}) -- an
     * accumulation year takes a separate, split-free path instead.
     *
     * <p>bondShare == 0 (every ALL_US / no-allocation account, taxableEquityShare == 1) takes a
     * SEPARATE, byte-identical-to-pre-C1 code path rather than the general proportional
     * split below — interestYield is then completely irrelevant, and this avoids any
     * BigDecimal scale/precision drift the general division-based split could otherwise
     * introduce for the single most common account shape (the backward-compat anchor).
     */
    private void applyTaxableYieldSplit(BigDecimal targetTotal) {
        BigDecimal bondShare = BigDecimal.ONE.subtract(taxableEquityShare);

        if (bondShare.signum() == 0) {
            lots.grow(tents.returns().taxable().subtract(dividendYield));
            BigDecimal dividend = targetTotal.subtract(lots.totalValue());
            lots.addLot(dividend);
            qualifiedDividendIncome = dividend.max(BigDecimal.ZERO);
            ordinaryInterestIncome = BigDecimal.ZERO;
        } else {
            BigDecimal equityYieldRate = taxableEquityShare.multiply(dividendYield);
            BigDecimal blendedYield = equityYieldRate.add(bondShare.multiply(interestYield));
            lots.grow(tents.returns().taxable().subtract(blendedYield));
            BigDecimal totalDistribution = targetTotal.subtract(lots.totalValue());
            lots.addLot(totalDistribution);

            if (blendedYield.signum() != 0) {
                BigDecimal dividendPortion = totalDistribution.multiply(equityYieldRate)
                        .divide(blendedYield, SCALE + 4, ROUNDING);
                qualifiedDividendIncome = dividendPortion.max(BigDecimal.ZERO);
                ordinaryInterestIncome = totalDistribution.subtract(dividendPortion).max(BigDecimal.ZERO);
            } else {
                qualifiedDividendIncome = BigDecimal.ZERO;
                ordinaryInterestIncome = BigDecimal.ZERO;
            }
        }
    }

    @Override
    public WithdrawalTaxResult executeWithdrawals(WithdrawalCycleInputs inputs) {
        BigDecimal totalNeed = inputs.need();
        int year = inputs.year();
        BigDecimal effectiveOtherIncome = inputs.effectiveOtherIncome();
        BigDecimal conversionAmount = inputs.conversionAmount();
        BigDecimal rmdAmount = inputs.rmdAmount();
        int age = inputs.age();
        BigDecimal alreadyChargedBaseTax = inputs.alreadyChargedBaseTax();
        BigDecimal extraPoolFundedTax = inputs.extraPoolFundedTax();
        BigDecimal federallyTaxedSocialSecurity = inputs.federallyTaxedSocialSecurity();
        BigDecimal netRentalIncome = inputs.netRentalIncome();

        // A fully income-covered year (totalNeed <= 0, e.g. pension/rental/SS covers spending)
        // still owes the RMD force-out and this year's dividend/LTCG tax below -- required
        // distributions and portfolio income are due regardless of whether the retiree needed
        // the cash (audit A2). Only the pool allocation for the spending draw itself is skipped
        // here; RMD forcing and taxation always run past this guard.
        boolean noSpendDraw = totalNeed.compareTo(BigDecimal.ZERO) <= 0;

        BigDecimal fromTaxable = BigDecimal.ZERO;
        BigDecimal fromTraditional = BigDecimal.ZERO;
        BigDecimal fromRoth = BigDecimal.ZERO;

        if (!noSpendDraw) {
            var withdrawalContext = new WithdrawalOrderStrategy.WithdrawalContext(
                    effectiveOtherIncome, conversionAmount, rmdAmount, age, year, earlyAccessAge);
            WithdrawalOrderStrategy strategy = WithdrawalOrderStrategy.forOrder(
                    withdrawalOrder, dynamicSequencingBracketRate, taxCalculator, filingStatus,
                    withdrawalContext);

            WithdrawalOrderStrategy.Result allocation =
                    strategy.execute(totalNeed, lots.totalValue(), traditional.total(), roth.total());
            if (allocation != null) {
                fromTaxable = allocation.fromTaxable();
                fromTraditional = allocation.fromTraditional();
                fromRoth = allocation.fromRoth();
            }
        }

        // Selling the taxable draw FIFO realizes a long-term capital gain (oldest lots first).
        // The traditional/Roth spend draws split proportionally by owner balance (task 4).
        BigDecimal spendSaleGain = lots.sellFifo(fromTaxable);
        traditional.debitProportional(fromTraditional);
        roth.debitProportional(fromRoth);

        // T18a-1: the RMD was already forced out of traditional (into a fresh taxable lot) by
        // a prior forceRmd() call -- BEFORE this method ran, and before any Roth conversion
        // (IRS ordering). rmdAmount here is that already-forced figure; fold it straight into
        // this year's traditional-sourced ordinary income alongside the spend draw's own
        // traditional portion -- no further pool mutation for RMD purposes happens here.
        BigDecimal rmdForced = rmdAmount != null ? rmdAmount.max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal traditionalSpendIncome = fromTraditional.add(rmdForced);

        // D5: gains realized earlier this year by conversion-tax sales (deductFromPools) join this
        // year's realized gain, and the bill's OWN funding sale is solved as a fixed point.
        BigDecimal baseRealizedGain = spendSaleGain.add(pendingTaxSaleGain);
        pendingTaxSaleGain = BigDecimal.ZERO;
        var inputsForBill = new WithdrawalTaxBillSolver.BillInputs(year, effectiveOtherIncome, conversionAmount,
                traditionalSpendIncome, baseRealizedGain, alreadyChargedBaseTax, extraPoolFundedTax,
                federallyTaxedSocialSecurity, netRentalIncome, age, lots.totalValue(), traditional.total(),
                filingStatus, qualifiedDividendIncome, ordinaryInterestIncome);
        var bill = billSolver.settle(inputsForBill, lots::peekFifoGain);
        bill.recordedBreakdown().ifPresent(b -> lastTaxBreakdown = Optional.of(b));

        // The converged gross-up draw is itself a traditional distribution: fold it into the
        // reported ordinary income so it (a) feeds the audit-B2 Social Security provisional-
        // income loop the same way the spend draw/RMD excess already do (see
        // YearFinanceResolver#realizedPortfolioTaxable, fed by this field) and (b) makes
        // RetirementTaxAnnotator's independent federal/state recompute agree with taxLiability.
        BigDecimal traditionalOrdinaryIncome = traditionalSpendIncome.add(bill.traditionalGrossUp());

        // One drain for tax + penalty: the cascade order is identical to the former two
        // back-to-back drains (taxable first, then traditional, then Roth).
        TaxSourceResult withdrawalTaxSource = bill.total().compareTo(BigDecimal.ZERO) > 0
                ? deductFromPools(bill.total()) : TaxSourceResult.ZERO;
        // deductFromPools just booked the funding sale's gain again; WithdrawalTaxBillSolver already
        // priced it, so drop it rather than taxing it twice.
        pendingTaxSaleGain = BigDecimal.ZERO;

        // T18a-5a: the aggregate includes the forced RMD excess (rmdForced) on top of the raw
        // spend-draw allocation, so it reconciles exactly with fromTaxable + traditionalOrdinaryIncome
        // (which already counts rmdForced) + fromRoth -- see the WithdrawalTaxResult javadoc.
        return new WithdrawalTaxResult(
                fromTaxable.add(fromTraditional).add(fromRoth).add(rmdForced),
                bill.total(),
                fromTaxable, traditionalOrdinaryIncome, fromRoth, withdrawalTaxSource, bill.ltcgTax(),
                bill.realizedLtcgIncome(), bill.earlyWithdrawalPenalty(), ordinaryInterestIncome);
    }

    /**
     * Snapshot of MultiPool's mutable state: a deep copy of the taxable FIFO lots plus the
     * scalar traditional/roth balances, this year's booked qualified dividend and (audit C1)
     * ordinary interest, and the last tax breakdown. Restoring returns the pool to its exact
     * post-growth, pre-withdrawal state.
     */
    record MultiPoolMemento(List<BigDecimal[]> lots, Map<PersonId, BigDecimal> traditional,
                            Map<PersonId, BigDecimal> roth, BigDecimal qualifiedDividendIncome,
                            BigDecimal ordinaryInterestIncome,
                            Optional<CombinedTaxResult> lastTaxBreakdown,
                            BigDecimal pendingTaxSaleGain, TentReservation tent) implements Memento {}

    @Override
    public Memento snapshot() {
        // Household task 4: the memento captures the FULL owner-keyed state (both pools' complete
        // maps, deep-copied so the SS-convergence loop can restore the same starting state
        // repeatedly). BigDecimal is immutable, so copying the map is enough.
        return new MultiPoolMemento(lots.snapshot(), traditional.snapshot(), roth.snapshot(),
                qualifiedDividendIncome, ordinaryInterestIncome, lastTaxBreakdown, pendingTaxSaleGain,
                tents.snapshot());
    }

    @Override
    public void restore(Memento memento) {
        if (memento instanceof MultiPoolMemento m) {
            lots.restore(m.lots());
            this.traditional.restore(m.traditional());
            this.roth.restore(m.roth());
            this.qualifiedDividendIncome = m.qualifiedDividendIncome();
            this.ordinaryInterestIncome = m.ordinaryInterestIncome();
            this.lastTaxBreakdown = m.lastTaxBreakdown();
            this.pendingTaxSaleGain = m.pendingTaxSaleGain();
            tents.restore(m.tent());
        }
    }

    @Override
    public ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome,
                                                   BigDecimal rmdAmount,
                                                   BigDecimal federallyTaxedSocialSecurity) {
        if (rothConversionStartYear != null && year < rothConversionStartYear) {
            return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
        }

        BigDecimal effectiveLimit;
        if ("fill_bracket".equals(rothConversionStrategy) && targetBracketRate != null && taxCalculator != null) {
            BigDecimal bracketCeiling = taxCalculator.computeMaxIncomeForTargetRate(
                    targetBracketRate, year, filingStatus);
            // RMD income already claims part of the target bracket, so it leaves less room for
            // conversions -- mirrors WithdrawalOrderStrategy.DynamicSequencingOrder's bracketSpace.
            BigDecimal space = bracketCeiling.subtract(effectiveOtherIncome).subtract(rmdAmount)
                    .max(BigDecimal.ZERO);
            effectiveLimit = space;
        } else {
            effectiveLimit = annualRothConversion;
        }

        if (effectiveLimit.compareTo(BigDecimal.ZERO) <= 0
                || traditional.total().signum() <= 0) {
            return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
        }
        return executeConversionWithAmount(effectiveLimit, year, effectiveOtherIncome,
                federallyTaxedSocialSecurity);
    }

    @Override
    public ConversionResult executeRothConversionOverride(int year, BigDecimal effectiveOtherIncome,
                                                            BigDecimal overrideAmount, BigDecimal rmdAmount,
                                                            BigDecimal federallyTaxedSocialSecurity) {
        // overrideAmount is an explicit, optimizer-scheduled dollar figure -- like the pre-existing
        // bracket-headroom check it bypasses, it does not respect RMD-consumed headroom either.
        if (overrideAmount.compareTo(BigDecimal.ZERO) <= 0
                || traditional.total().signum() <= 0) {
            return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
        }
        return executeConversionWithAmount(overrideAmount, year, effectiveOtherIncome,
                federallyTaxedSocialSecurity);
    }

    /**
     * Executes a Roth conversion of the given amount: transfers from traditional to Roth,
     * computes tax on the conversion, and deducts tax from pools. Shared by both
     * executeRothConversion (bracket/fixed amount) and executeRothConversionOverride
     * (optimizer-scheduled amount). The state base exempts the federally-taxed Social Security
     * amount (audit C3) but adds no LTCG income -- the year's realized LTCG/dividends are taxed
     * once, in {@link #executeWithdrawals}'s bundle; the marginal subtraction there recomputes
     * THIS base with identical arguments so the netting is exact.
     */
    private ConversionResult executeConversionWithAmount(BigDecimal conversionLimit, int year,
                                                           BigDecimal effectiveOtherIncome,
                                                           BigDecimal federallyTaxedSocialSecurity) {
        // Household task 4: draw the conversion from traditional split proportionally by owner,
        // and credit each owner's OWN Roth with what came from that owner's traditional (a Roth
        // conversion stays within one person's accounts) — conservation-preserving and
        // byte-identical for a single owner.
        BigDecimal actual = conversionLimit.min(traditional.total());
        // An in-kind transfer: settle every earlier debit first (so a spent sleeve stays spent), then clamp
        // again once the converted dollars have landed, so displaced sleeves can follow the money.
        tents.clamp();
        var converted = traditional.debitProportional(actual);
        for (var entry : converted.entrySet()) {
            roth.credit(entry.getKey(), entry.getValue());
        }
        tents.clamp();

        if (taxCalculator != null) {
            BigDecimal taxableIncome = actual.add(effectiveOtherIncome);
            var detailed = taxCalculator.computeDetailedTax(taxableIncome, year, filingStatus,
                    BigDecimal.ZERO, federallyTaxedSocialSecurity);
            BigDecimal tax = detailed.totalTax();
            lastTaxBreakdown = Optional.of(detailed);
            TaxSourceResult taxSource = deductFromPools(tax);
            return new ConversionResult(actual, tax, taxSource);
        }
        return new ConversionResult(actual, BigDecimal.ZERO, TaxSourceResult.ZERO);
    }

    @Override
    public BigDecimal forceRmd(BigDecimal rmdAmount) {
        if (rmdAmount == null || rmdAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal forced = rmdAmount.min(traditional.total()).max(BigDecimal.ZERO);
        if (forced.compareTo(BigDecimal.ZERO) > 0) {
            tents.clamp();   // an in-kind transfer: settle earlier debits, then follow the money (below)
            traditional.debitProportional(forced);
            lots.addLot(forced);
            tents.clamp();
        }
        return forced;
    }

    @Override
    public BigDecimal forceRmd(PersonId owner, BigDecimal rmdAmount) {
        if (rmdAmount == null || rmdAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        tents.clamp();   // an in-kind transfer: settle earlier debits before the owner's slice is debited
        BigDecimal forced = traditional.forceOwner(owner, rmdAmount);
        if (forced.compareTo(BigDecimal.ZERO) > 0) {
            lots.addLot(forced);
            tents.clamp();
        }
        return forced;
    }

    @Override
    public void applyFirstDeathTransition(PersonId deceased, PersonId survivor, boolean communityProperty) {
        // Step 2 (spec order): spousal rollover — deceased's trad/roth transfer to survivor.
        // Conservation-preserving (transferAll moves, never mints); the tax cascade thereafter
        // sees one combined per-owner slice under the survivor.
        traditional.transferAll(deceased, survivor);
        roth.transferAll(deceased, survivor);
        // Step 3: EXACT per-owner basis step-up on the commingled taxable lots (HP1). Each lot
        // steps by its own owner's statutory rate — joint at the community/common-law rate, the
        // decedent's own lots fully, the survivor's own lots not at all — and decedent-owned lots
        // retag to the survivor. Replaces the T5 single initial-balance-weighted blended factor.
        BigDecimal jointFactor = communityProperty ? BigDecimal.ONE : new BigDecimal("0.5");
        lots.stepUpByOwner(LotOwner.forPerson(deceased), jointFactor);
        // Step 5: filing status flips to single for this and every later year.
        this.filingStatus = FilingStatus.SINGLE;
    }

    @Override
    public void floorAtZero() {
        // The taxable lots can never go negative (sellFifo caps every draw at the current total);
        // only the traditional/Roth pools can be driven below zero by the tax cascade. Each owner's
        // slice is floored independently (task 4).
        traditional.floorAtZero();
        roth.floorAtZero();
    }

    @Override
    public Optional<CombinedTaxResult> getLastTaxBreakdown() {
        return lastTaxBreakdown;
    }

    @Override
    public void depositToTaxable(BigDecimal amount) {
        tents.clamp();
        lots.addLot(amount);   // reinvested surplus enters at cost (basis = value)
    }

    @Override
    public ProjectionYearDto buildYearDto(YearDtoContext ctx) {
        var inputs = new MultiPoolYearDtoBuilder.YearDtoInputs(
                ctx.year(), ctx.age(), ctx.startBalance(), ctx.contributions(), ctx.totalGrowth(),
                ctx.withdrawals(), ctx.retired(),
                ctx.conversionAmount(), ctx.taxLiability(), ctx.growthResult(),
                ctx.withdrawalFromTaxable(), ctx.withdrawalFromTraditional(), ctx.withdrawalFromRoth(),
                ctx.combinedTaxSource(), getTotal(), lots.totalValue(), traditional.total(), roth.total(),
                ctx.rmdAmount(), ctx.ltcgTax(), ctx.earlyWithdrawalPenalty());

        // The breakdown is consumed once per year, then cleared so the next year starts fresh.
        CombinedTaxResult breakdown = lastTaxBreakdown.orElse(null);
        lastTaxBreakdown = Optional.empty();
        return MultiPoolYearDtoBuilder.build(inputs, breakdown);
    }

    @Override
    public BigDecimal getMagi() {
        return otherIncome;
    }

    @Override
    public FilingStatus getFilingStatus() {
        return filingStatus;
    }

    @Override
    public boolean hasPendingTaxSaleGain() {
        return pendingTaxSaleGain.signum() != 0;
    }

    @Override
    public boolean processIncomeSourcesEveryYear() {
        return true;
    }

    @Override
    public boolean tracksSETax() {
        return true;
    }

    @Override
    public BigDecimal computeEffectiveOtherIncome(BigDecimal activeIncome, BigDecimal incomeSourceCash) {
        return otherIncome.add(activeIncome).add(incomeSourceCash);
    }

    @Override
    public String logTag() {
        return "Projection with pools";
    }

    /**
     * Physically drains {@code amount} taxable-first, then traditional, then Roth. Callers that
     * fund an ordinary/LTCG tax bill run {@code WithdrawalTaxBillSolver}'s gross-up FIRST so the
     * traditional slice this cascade is about to drain has already been grossed up and folded into the
     * year's reported ordinary income (audit C2) -- by the time {@code amount} reaches here it is
     * the FINAL, already-grossed-up bill; this method itself stays a plain three-pool drain.
     */
    private TaxSourceResult deductFromPools(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return TaxSourceResult.ZERO;
        }
        BigDecimal remaining = amount;

        // Pay from taxable first, selling lots FIFO. D5: the realized gain pends until the year's
        // withdrawal cycle taxes it (executeWithdrawals folds it in, or prices it via
        // WithdrawalTaxBillSolver when this drain IS that cycle's bill).
        BigDecimal fromTax = remaining.min(lots.totalValue());
        pendingTaxSaleGain = pendingTaxSaleGain.add(lots.sellFifo(fromTax));
        remaining = remaining.subtract(fromTax);

        // Traditional and Roth slices of the bill split proportionally by owner balance (task 4);
        // the Roth catch-all can drive an owner negative, which floorAtZero later clears.
        BigDecimal fromTrad = remaining.min(traditional.total());
        traditional.debitProportional(fromTrad);
        remaining = remaining.subtract(fromTrad);

        // Report only the Roth slice that was actually there to pay: the part of the catch-all
        // debit beyond the Roth balance is an UNFUNDED bill (floorAtZero erases it at year end),
        // and crediting it as tax_paid_from_roth would let SpendingFeasibilityAnalyzer count
        // money the household never had as available.
        BigDecimal fundedFromRoth = remaining.min(roth.total().max(BigDecimal.ZERO));
        roth.debitProportional(remaining);
        return new TaxSourceResult(fromTax, fromTrad, fundedFromRoth);
    }
}
