package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.LotOwner;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
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
     * Maximum polish passes for the audit-C2 traditional-tax gross-up AFTER the closed-form
     * warm start (see {@link #growTraditionalGrossUp}). Raised 5 -> 10 (T10 review): plain
     * cold-start iteration contracts only at the marginal rate m per pass, leaving multi-dollar
     * residuals at m >= 0.22 within 5 passes; with the warm start the loop usually breaks on
     * pass 1, so the higher cap costs nothing in practice (nested B2 x C2 worst case is
     * 4 x ~12 bracket computations per projection year -- trivial).
     */
    private static final int MAX_GROSS_UP_ITERATIONS = 10;
    /**
     * Convergence tolerance (dollars) for the audit-C2 gross-up fixed point -- the same $1
     * tolerance {@code YearFinanceResolver} uses for its Social Security fixed point.
     */
    private static final BigDecimal GROSS_UP_TOLERANCE = BigDecimal.ONE;
    /**
     * Defensive cap on the chord marginal rate used for the warm-start jump in
     * {@link #growTraditionalGrossUp}: a real combined federal+state marginal rate cannot reach
     * 100% (which would make the {@code implied/(1-m)} closed form diverge); 50% comfortably
     * covers the worst realistic combination (37% federal + 13.3% CA). The cap only shortens
     * the JUMP -- the polish loop still converges to the true fixed point afterwards.
     */
    private static final BigDecimal GROSS_UP_WARM_START_RATE_CAP = new BigDecimal("0.50");
    /** D5: polish passes after the closed-form warm start in {@link #settleTaxSaleGain}. */
    private static final int MAX_TAX_SALE_GAIN_ITERATIONS = 3;
    /** IRC 72(t) -- 10% additional tax on early (pre-59½) traditional-account distributions,
     * a statutory constant (T18a-4). */
    private static final BigDecimal EARLY_WITHDRAWAL_PENALTY_RATE = new BigDecimal("0.10");
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

    // Capital-gains taxation collaborators (null calculator ⇒ taxable pool stays untaxed,
    // preserving pre-lots behavior for callers that don't wire capital gains).
    private final CapitalGainsTaxCalculator capitalGainsTaxCalculator;
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
    private final BigDecimal inflationRate;
    private final int baseYear;
    private final int earlyAccessAge;
    /**
     * Source of the federal standard deduction, used ONLY to net the LTCG stacking floor down
     * to the same base the ordinary tax computed on (see {@link #resolveOrdinaryDeduction}). Null
     * for callers that don't wire it, in which case the floor stays gross (pre-fix behavior).
     */
    private final FederalTaxCalculator federalTaxCalculator;

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
        this.capitalGainsTaxCalculator = config.capitalGainsTaxCalculator();
        this.dividendYield = config.dividendYield();
        this.interestYield = config.interestYield();
        this.inflationRate = config.inflationRate();
        this.baseYear = config.baseYear();
        this.earlyAccessAge = config.earlyAccessAge();
        this.federalTaxCalculator = config.federalTaxCalculator();
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
        TaxBill bill = settleTaxSaleGain(new BillInputs(year, effectiveOtherIncome, conversionAmount,
                traditionalSpendIncome, baseRealizedGain, alreadyChargedBaseTax, extraPoolFundedTax,
                federallyTaxedSocialSecurity, netRentalIncome, age, lots.totalValue(), traditional.total()));

        // The converged gross-up draw is itself a traditional distribution: fold it into the
        // reported ordinary income so it (a) feeds the audit-B2 Social Security provisional-
        // income loop the same way the spend draw/RMD excess already do (see
        // YearFinanceResolver#realizedPortfolioTaxable, fed by this field) and (b) makes
        // RetirementTaxAnnotator's independent federal/state recompute agree with taxLiability.
        BigDecimal traditionalOrdinaryIncome = traditionalSpendIncome.add(bill.grossUp().traditionalGrossUp());

        // One drain for tax + penalty: the cascade order is identical to the former two
        // back-to-back drains (taxable first, then traditional, then Roth).
        TaxSourceResult withdrawalTaxSource = bill.total().compareTo(BigDecimal.ZERO) > 0
                ? deductFromPools(bill.total()) : TaxSourceResult.ZERO;
        // deductFromPools just booked the funding sale's gain again; settleTaxSaleGain already
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

    /** D5: everything the year's withdrawal-cycle bill depends on EXCEPT the gain of the taxable
     * sale that funds it. {@code taxableAvail}/{@code traditionalAvail} are frozen post-spend-draw,
     * pre-drain snapshots (the C2 gross-up contract). */
    private record BillInputs(int year, BigDecimal effectiveOtherIncome, BigDecimal conversionAmount,
                              BigDecimal traditionalSpendIncome, BigDecimal baseRealizedGain,
                              BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax,
                              BigDecimal federallyTaxedSocialSecurity, BigDecimal netRentalIncome, int age,
                              BigDecimal taxableAvail, BigDecimal traditionalAvail) {}

    /** D5: one evaluation of the year's full bill (ordinary bundle + LTCG incl. the gross-up
     * re-stack + C2 gross-up + the 10% early-withdrawal penalty) at a candidate tax-funding-sale
     * gain. {@code ltcgTax} already includes the re-stack delta. */
    private record TaxBill(BigDecimal total, BigDecimal ltcgTax, BigDecimal realizedLtcgIncome,
                           GrossUpResult grossUp, BigDecimal earlyWithdrawalPenalty) {}

    private TaxBill computeBill(BillInputs in, BigDecimal taxSaleGain) {
        BigDecimal realizedGain = in.baseRealizedGain().add(taxSaleGain);
        // Realized LTCG + qualified-dividend INCOME, floored at zero: a net realized loss offsets
        // the year's gains/dividends but never yields a negative tax (its small AGI offset stays
        // out of scope). Computed BEFORE the ordinary-tax bundle because it feeds two places: the
        // state base of that bundle for states that tax capital gains as ordinary income (audit
        // C3), and AGI-ex-SS for the Social Security provisional-income convergence (audit B2).
        BigDecimal realizedLtcgIncome = realizedGain.add(qualifiedDividendIncome).max(BigDecimal.ZERO);

        // Audit C1: this year's ordinary-interest income (the taxable pool's bond+cash sleeve,
        // booked in applyGrowth) joins the ORDINARY bundle directly -- unlike qualifiedDividend/
        // realizedGain, it is NOT LTCG income, so it does NOT flow through realizedLtcgIncome
        // above (no LTCG-rate treatment, no LTCG-bracket floor stacking). It DOES flow through
        // the same ordinary path effectiveOtherIncome/conversionAmount/traditionalOrdinaryIncome
        // already take: the full taxableIncome bundle below (which feeds the Social Security
        // provisional-income convergence via realizedPortfolioTaxable -- see
        // YearFinanceResolver -- and the state base for capital-gains-as-ordinary states).
        BigDecimal taxableIncome = in.traditionalSpendIncome().add(in.effectiveOtherIncome())
                .add(in.conversionAmount()).add(ordinaryInterestIncome);
        var ordinaryTax = computeOrdinaryTax(taxableIncome, in.year(), in.effectiveOtherIncome(),
                in.conversionAmount(), in.alreadyChargedBaseTax(), realizedLtcgIncome,
                in.federallyTaxedSocialSecurity());
        CombinedTaxResult detailed = ordinaryTax.detailed();

        // Long-term capital-gains tax on the realized FIFO gain + this year's qualified dividend,
        // stacked on ordinary income against the 0/15/20 LTCG brackets (+ deflated NIIT, T18a-3:
        // now including net rental income in the NIIT base). This runs whenever executeWithdrawals
        // runs -- both in retirement and, since T18a-2, in a still-working RMD-age year. LTCG is a
        // federal tax, so it belongs in the federal-tax breakdown -- it folds into taxLiability and
        // drains the pools via the same cascade as the ordinary withdrawal tax, AND is returned
        // separately so the engine can fold it into the year's federalTax field. It is
        // deliberately NOT added to lastTaxBreakdown here: for retired years RetirementTaxAnnotator
        // recomputes (and overwrites) the DTO's federal/state breakdown from scratch downstream of
        // this call, so that is where the fold actually has to happen -- see
        // RetirementTaxAnnotator#annotate.
        BigDecimal ltcgTax = computeLtcgTax(realizedGain, taxableIncome, in.year(), detailed,
                in.netRentalIncome());

        // C2: a tax payment sourced from the traditional pool is ITSELF an ordinary-income
        // distribution once withdrawn -- converge the traditional-funded slice of the bill to
        // a fixed point (see growTraditionalGrossUp) BEFORE physically draining the pools, so
        // the extra draw is debited, taxed, and reported as ordinary income all in one pass.
        // NOTE: growTraditionalGrossUp's internal recomputes call computeOrdinaryTax, which
        // ALREADY updates lastTaxBreakdown as a side effect (with its own conditional-recording
        // logic -- see that method's javadoc) on every call it makes, including the LAST one when
        // the loop actually iterates. Nothing further needs to happen here: when the loop doesn't
        // iterate (no traditional slice to gross up), lastTaxBreakdown is untouched by this method
        // and stays exactly what the pre-loop computeOrdinaryTax call above already left it as --
        // byte-identical to pre-C2 behavior.
        var grossUp = growTraditionalGrossUp(taxableIncome, in.year(), in.effectiveOtherIncome(),
                in.conversionAmount(), in.alreadyChargedBaseTax(), realizedLtcgIncome,
                in.federallyTaxedSocialSecurity(), ltcgTax, in.extraPoolFundedTax(), ordinaryTax,
                in.taxableAvail(), in.traditionalAvail());
        // Phase 1a reconciliation: LTCG stacks on the FULL year's ordinary income, including the
        // C2 gross-up slice. Re-price once on the final base; the (non-negative) delta joins the
        // pool-funded tax. Its own funding draw is not re-stacked -- the same documented
        // second-order category as the early-withdrawal penalty's funding.
        BigDecimal ltcgRestackDelta = BigDecimal.ZERO;
        if (grossUp.traditionalGrossUp().signum() > 0 && realizedLtcgIncome.signum() > 0) {
            BigDecimal restackedLtcgTax = computeLtcgTax(realizedGain,
                    taxableIncome.add(grossUp.traditionalGrossUp()), in.year(), detailed,
                    in.netRentalIncome());
            ltcgRestackDelta = restackedLtcgTax.subtract(ltcgTax).max(BigDecimal.ZERO);
            ltcgTax = ltcgTax.add(ltcgRestackDelta);
        }

        // T18a-4: 10% IRC 72(t) additional tax on traditional DISTRIBUTIONS before early-access
        // age (age < earlyAccessAge from AgeMilestones -- the 59 1/2 calendar year, legacy 60 -- the
        // SAME threshold the withdrawal-order strategies already use to steer clear of early
        // traditional draws). Applies to the year's FULL traditional-sourced distribution (spend
        // draw + RMD force-out + the C2 tax-funding gross-up slice) -- Roth conversions are OUT OF
        // SCOPE (the converted dollars move internally to Roth, not withdrawn to the household).
        // It is NOT part of the C2 gross-up's own fixed point (re-stacking the penalty's funding
        // draw as further taxable/penalizable income is a documented, out-of-scope second-order
        // effect), but D5 prices the taxable sale that funds it via settleTaxSaleGain.
        BigDecimal penalty = in.age() < earlyAccessAge
                ? in.traditionalSpendIncome().add(grossUp.traditionalGrossUp())
                        .multiply(EARLY_WITHDRAWAL_PENALTY_RATE)
                : BigDecimal.ZERO;
        return new TaxBill(grossUp.tax().add(ltcgRestackDelta).add(penalty), ltcgTax, realizedLtcgIncome,
                grossUp, penalty);
    }

    /**
     * D5: solves {@code bill = Bill(gainOf(min(bill, taxableAvail)))} -- the taxable sale that pays
     * the year's bill realizes a gain, which raises the bill. Same convergence shape as
     * {@link #growTraditionalGrossUp}: one naive pass, a closed-form warm jump off the measured
     * tax-per-sale-dollar chord ({@code sale = bill0 / (1 - r)}), then at most
     * {@link #MAX_TAX_SALE_GAIN_ITERATIONS} polish passes to a $1 gain tolerance. Never mutates a
     * pool ({@code peekFifoGain} only); the LAST {@code computeBill} call is always at the returned
     * gain, so {@code lastTaxBreakdown} reflects the settled bill. No embedded gain => returns the
     * gain-0 bill, byte-identical to pre-D5.
     */
    private TaxBill settleTaxSaleGain(BillInputs in) {
        TaxBill bill = computeBill(in, BigDecimal.ZERO);
        BigDecimal sale0 = bill.total().min(in.taxableAvail());
        BigDecimal gain = lots.peekFifoGain(sale0);
        if (gain.abs().compareTo(GROSS_UP_TOLERANCE) < 0) {
            return bill;
        }
        TaxBill atGain = computeBill(in, gain);
        BigDecimal taxPerSaleDollar = atGain.total().subtract(bill.total())
                .divide(sale0, SCALE + 4, ROUNDING)
                .max(BigDecimal.ZERO)
                .min(GROSS_UP_WARM_START_RATE_CAP);
        BigDecimal warmSale = bill.total()
                .divide(BigDecimal.ONE.subtract(taxPerSaleDollar), SCALE + 4, ROUNDING)
                .min(in.taxableAvail());
        BigDecimal settledGain = lots.peekFifoGain(warmSale);
        TaxBill current = computeBill(in, settledGain);
        for (int i = 0; i < MAX_TAX_SALE_GAIN_ITERATIONS; i++) {
            BigDecimal impliedGain = lots.peekFifoGain(current.total().min(in.taxableAvail()));
            if (impliedGain.subtract(settledGain).abs().compareTo(GROSS_UP_TOLERANCE) < 0) {
                break;
            }
            settledGain = impliedGain;
            current = computeBill(in, settledGain);
        }
        return current;
    }

    /** The year's ordinary-income tax bundle: the net pool-funded tax plus the detailed result. */
    private record OrdinaryTax(BigDecimal tax, @Nullable CombinedTaxResult detailed) {
        static final OrdinaryTax NONE = new OrdinaryTax(BigDecimal.ZERO, null);
    }

    /**
     * Computes the withdrawal cycle's ordinary-income tax bundle and records it in
     * {@code lastTaxBreakdown}. The state base of this bundle adds the year's realized
     * LTCG/dividend income (for states taxing capital gains as ordinary income) and exempts the
     * federally-taxed Social Security amount (for states that exempt it) -- audit C3; the
     * DISPLAYED breakdown ({@code RetirementTaxAnnotator}) recomputes with the SAME inputs, so
     * display and pool funding agree.
     *
     * <p>The {@code realizedLtcgIncome > 0} guard leg covers a retiree with ZERO ordinary income
     * but realized gains/dividends: a state that taxes capital gains as ordinary income still
     * owes state tax on them. For every other strategy that extra computation is all-zero and
     * {@code lastTaxBreakdown} stays empty (see the recording condition below), preserving
     * pre-C3 behavior byte-for-byte.
     *
     * <p>Tax already charged elsewhere this year is netted out to avoid double-counting through
     * separate progressive-bracket calculations:
     * <ul>
     *   <li>{@code conversionAmount > 0}: its tax was already computed and paid by
     *       {@code executeConversionWithAmount} (called earlier in the year) -- recompute the
     *       base tax fresh (bracket-accurate marginal subtraction) since that call's own paid
     *       amount isn't threaded through here. The recompute MUST use the same state-base
     *       adjustments the conversion bundle used (same SS exemption, no LTCG income -- the
     *       LTCG state addition belongs to THIS bundle only) so the subtraction nets exactly
     *       and full-bundle &ge; base-bundle stays monotone (audit C3).</li>
     *   <li>{@code alreadyChargedBaseTax > 0}: {@code RetirementWithdrawalProcessor}'s surplus
     *       branch already charged exactly this dollar amount against
     *       {@code (effectiveOtherIncome + conversionAmount)}, whether funded from the year's
     *       cash surplus or left in {@code extraPoolFundedTax} -- subtract the EXPLICIT figure
     *       directly (audit A4 / T2 review: replaces the old {@code totalNeed<=0} "noSpendDraw"
     *       proxy, which mis-fires for {@code GuardrailSpendingInput} -- see the interface
     *       javadoc). This bundle then contributes only the marginal tax caused by the forced
     *       RMD/spend draw on top of that base.</li>
     *   <li>Otherwise (positive spend draw, no conversion, nothing pre-charged) this is the
     *       year's first and only ordinary-tax computation, so the full bundle stands as-is.</li>
     * </ul>
     */
    private OrdinaryTax computeOrdinaryTax(BigDecimal taxableIncome, int year,
                                            BigDecimal effectiveOtherIncome, BigDecimal conversionAmount,
                                            BigDecimal alreadyChargedBaseTax, BigDecimal realizedLtcgIncome,
                                            BigDecimal federallyTaxedSocialSecurity) {
        if (taxCalculator == null
                || (taxableIncome.compareTo(BigDecimal.ZERO) <= 0
                    && realizedLtcgIncome.compareTo(BigDecimal.ZERO) <= 0)) {
            return OrdinaryTax.NONE;
        }

        var detailed = taxCalculator.computeDetailedTax(taxableIncome, year, filingStatus,
                realizedLtcgIncome, federallyTaxedSocialSecurity);

        BigDecimal tax;
        if (conversionAmount.compareTo(BigDecimal.ZERO) > 0) {
            var baseTax = taxCalculator.computeDetailedTax(
                    conversionAmount.add(effectiveOtherIncome), year, filingStatus,
                    BigDecimal.ZERO, federallyTaxedSocialSecurity);
            tax = detailed.totalTax().subtract(baseTax.totalTax()).max(BigDecimal.ZERO);
        } else if (alreadyChargedBaseTax.compareTo(BigDecimal.ZERO) > 0) {
            tax = detailed.totalTax().subtract(alreadyChargedBaseTax).max(BigDecimal.ZERO);
        } else {
            tax = detailed.totalTax();
        }

        // Record whenever ordinary income exists (pre-C3 behavior), or when the new
        // zero-ordinary-income leg actually produced state tax; an all-zero result on that new
        // leg leaves the breakdown empty exactly as before.
        if (taxableIncome.compareTo(BigDecimal.ZERO) > 0
                || detailed.totalTax().compareTo(BigDecimal.ZERO) > 0) {
            lastTaxBreakdown = Optional.of(detailed);
        }
        return new OrdinaryTax(tax, detailed);
    }

    /**
     * The converged audit-C2 gross-up: how much EXTRA traditional income the fixed point below
     * settled on, and the resulting total bill. {@code lastTaxBreakdown} is NOT threaded through
     * here -- {@code computeOrdinaryTax} already updates it as a side effect on every call it
     * makes (including the search's last one), with its own conditional-recording logic, so the
     * field is left exactly as that method's last call set it.
     */
    private record GrossUpResult(BigDecimal traditionalGrossUp, BigDecimal tax) {}

    /**
     * Audit C2: a tax payment sourced from the traditional pool is ITSELF an ordinary-income
     * distribution once withdrawn -- it must fund its own tax, which (once the taxable pool is
     * exhausted) again comes from traditional, and so on: an infinite geometric series that
     * converges because the marginal tax rate is always &lt;1. This method finds that fixed
     * point: {@code bill = TaxBill(baseTaxableIncome + traditionalSlice(bill))}.
     *
     * <p>Convergence strategy (T10 review -- plain cold-start iteration contracts only at rate m
     * per pass and left multi-dollar residuals at realistic marginal rates within the old 5-pass
     * cap):
     * <ol>
     *   <li><b>First pass</b>: take the naive slice {@code implied1 = peek(bill0)} and recompute
     *       the bill at it. If the slice is under the $1 tolerance there is no traditional
     *       exposure at all -- return immediately, byte-identical to pre-fix behavior.</li>
     *   <li><b>Closed-form warm jump</b>: the first pass's two bill evaluations give the exact
     *       chord marginal rate {@code m = (bill1 - bill0) / implied1} over the slice range --
     *       measured through the SAME 5-arg {@code computeOrdinaryTax} seam, so it captures the
     *       combined federal+state rate plus every netting leg exactly (a raw federal-bracket
     *       lookup would understate m whenever state tax is in the bill). Jump straight to the
     *       affine fixed point {@code implied1 / (1 - m)} (capped at
     *       {@link #GROSS_UP_WARM_START_RATE_CAP} and {@code traditionalAvail}) and recompute
     *       there. When no bracket boundary sits inside the jump range this IS the fixed point
     *       and the polish loop below breaks on its first check.</li>
     *   <li><b>Polish loop</b>: plain peek/recompute passes (up to
     *       {@link #MAX_GROSS_UP_ITERATIONS}) correct any bracket-crossing error left by the
     *       jump, breaking when the peeked slice moves by less than
     *       {@link #GROSS_UP_TOLERANCE} -- mirrors {@code YearFinanceResolver}'s Social
     *       Security convergence loop in both shape and tolerance.</li>
     * </ol>
     * {@code peekTraditionalSlice} is a pure function of the FROZEN {@code taxableAvail} /
     * {@code traditionalAvail} snapshots taken just before the tax cascade runs, so no pool is
     * touched during the search.
     *
     * <p>Only the ORDINARY tax bundle is re-stacked each pass; {@code ltcgTax} and {@code
     * extraPoolFundedTax} are added on top unchanged inside the search (the caller re-prices LTCG
     * once against the final ordinary floor -- see {@link #computeBill}). The Roth
     * slice of a tax payment is never grossed up (Roth withdrawals are tax-free) and the taxable
     * slice's realized gain is priced by {@link #settleTaxSaleGain} (D5) -- this method only ever
     * grows the TRADITIONAL slice.
     *
     * <p>The physical drain happens once, afterward, via the caller's own {@link
     * #deductFromPools} call on the returned {@code tax} -- this method never mutates a pool.
     */
    private GrossUpResult growTraditionalGrossUp(BigDecimal baseTaxableIncome, int year,
            BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, BigDecimal alreadyChargedBaseTax,
            BigDecimal realizedLtcgIncome, BigDecimal federallyTaxedSocialSecurity,
            BigDecimal ltcgTax, BigDecimal extraPoolFundedTax, OrdinaryTax initialOrdinary,
            BigDecimal taxableAvail, BigDecimal traditionalAvail) {

        BigDecimal constantTax = ltcgTax.add(extraPoolFundedTax.max(BigDecimal.ZERO));
        BigDecimal bill0 = initialOrdinary.tax().add(constantTax);

        // First pass: naive slice off the initial bill. Under-tolerance => taxable (plus Roth
        // headroom) covers everything reachable -- no gross-up, exact pre-fix behavior.
        BigDecimal implied1 = peekTraditionalSlice(bill0, taxableAvail, traditionalAvail);
        if (implied1.compareTo(GROSS_UP_TOLERANCE) < 0) {
            return new GrossUpResult(BigDecimal.ZERO, bill0);
        }
        BigDecimal slice = implied1;
        BigDecimal bill = recomputeBill(baseTaxableIncome, slice, year, effectiveOtherIncome,
                conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome, federallyTaxedSocialSecurity,
                constantTax);

        // Closed-form warm jump off the measured chord rate (constantTax cancels in the delta).
        BigDecimal chordRate = bill.subtract(bill0)
                .divide(implied1, SCALE + 4, ROUNDING)
                .min(GROSS_UP_WARM_START_RATE_CAP);
        if (chordRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal jump = implied1
                    .divide(BigDecimal.ONE.subtract(chordRate), SCALE + 4, ROUNDING)
                    .min(traditionalAvail);
            if (jump.subtract(slice).abs().compareTo(GROSS_UP_TOLERANCE) >= 0) {
                slice = jump;
                bill = recomputeBill(baseTaxableIncome, slice, year, effectiveOtherIncome,
                        conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome,
                        federallyTaxedSocialSecurity, constantTax);
            }
        }

        // Polish: plain fixed-point passes to absorb any bracket crossing inside the jump range.
        for (int i = 0; i < MAX_GROSS_UP_ITERATIONS; i++) {
            BigDecimal impliedSlice = peekTraditionalSlice(bill, taxableAvail, traditionalAvail);
            if (impliedSlice.subtract(slice).abs().compareTo(GROSS_UP_TOLERANCE) < 0) {
                break;
            }
            slice = impliedSlice;
            bill = recomputeBill(baseTaxableIncome, slice, year, effectiveOtherIncome,
                    conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome,
                    federallyTaxedSocialSecurity, constantTax);
        }
        return new GrossUpResult(slice, bill);
    }

    /** One gross-up bill evaluation: the ordinary bundle at {@code base + slice} plus the
     * constant (non-re-stacked) ltcg/extra-pool-funded additions. Also refreshes
     * {@code lastTaxBreakdown} via {@code computeOrdinaryTax}'s own recording side effect. */
    private BigDecimal recomputeBill(BigDecimal baseTaxableIncome, BigDecimal slice, int year,
            BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, BigDecimal alreadyChargedBaseTax,
            BigDecimal realizedLtcgIncome, BigDecimal federallyTaxedSocialSecurity,
            BigDecimal constantTax) {
        var recomputed = computeOrdinaryTax(baseTaxableIncome.add(slice), year, effectiveOtherIncome,
                conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome, federallyTaxedSocialSecurity);
        return recomputed.tax().add(constantTax);
    }

    /**
     * The traditional-sourced dollars of a hypothetical {@code tax} payment cascading
     * taxable-first against FROZEN (pre-drain) pool balances -- mirrors {@link
     * #deductFromPools}'s cascade order without mutating anything, so {@link
     * #growTraditionalGrossUp}'s fixed-point search can explore candidate bills before the real
     * drain happens.
     */
    private static BigDecimal peekTraditionalSlice(BigDecimal tax, BigDecimal taxableAvail,
                                                    BigDecimal traditionalAvail) {
        BigDecimal afterTaxable = tax.subtract(taxableAvail).max(BigDecimal.ZERO);
        return afterTaxable.min(traditionalAvail).max(BigDecimal.ZERO);
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

    /**
     * LTCG tax on {@code realizedGain + qualifiedDividendIncome}, stacked on the year's ordinary
     * taxable income. Returns zero when no capital-gains calculator is wired or the net LTCG income
     * is non-positive (a realized loss nets against the dividend). {@code magi ≈ gross ordinary +
     * LTCG} (MAGI is NOT deduction-reduced), but the STACKING FLOOR is netted by
     * {@link #resolveOrdinaryDeduction} down to the same base {@code ordinaryTaxDetail} (the
     * ordinary-tax result already computed on {@code ordinaryTaxableIncome}, if any) used --
     * otherwise the deduction dollars would be treated as below-the-line for the ordinary tax AND
     * above-the-line for the LTCG floor, overstating LTCG tax.
     */
    private BigDecimal computeLtcgTax(BigDecimal realizedGain, BigDecimal ordinaryTaxableIncome, int year,
                                       CombinedTaxResult ordinaryTaxDetail, BigDecimal netRentalIncome) {
        if (capitalGainsTaxCalculator == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal ltcgIncome = realizedGain.add(qualifiedDividendIncome);
        if (ltcgIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal magi = ordinaryTaxableIncome.add(ltcgIncome);
        BigDecimal deduction = resolveOrdinaryDeduction(ordinaryTaxDetail, year);
        BigDecimal ordinaryForLtcg = ordinaryTaxableIncome.subtract(deduction).max(BigDecimal.ZERO);
        // T18a-3: net rental income joins the NIIT's Net Investment Income base (NOT the LTCG
        // bracket-tax base) -- see CapitalGainsTaxCalculator's rental overload.
        return capitalGainsTaxCalculator.computeLtcgTax(ordinaryForLtcg, ltcgIncome, year,
                filingStatus, year - baseYear, inflationRate, magi, netRentalIncome);
    }

    /**
     * The deduction the ordinary tax actually used, for netting the LTCG stacking floor onto the
     * same base. Prefers the itemized figure when {@code ordinaryTaxDetail} shows itemizing won
     * (mirrors {@code CombinedTaxCalculator.computeTax}'s own itemized-vs-standard choice);
     * otherwise the tax strategy's age-aware standard deduction, falling back to the age-unaware
     * federal figure for (year, filingStatus). Returns ZERO
     * -- i.e. the floor stays gross, pre-fix -- when neither is available (no ordinary-tax result
     * AND no standard-deduction source wired).
     */
    private BigDecimal resolveOrdinaryDeduction(CombinedTaxResult ordinaryTaxDetail, int year) {
        if (ordinaryTaxDetail != null && ordinaryTaxDetail.usedItemized()) {
            return ordinaryTaxDetail.itemizedDeductions();
        }
        // Phase 1a: prefer the strategy's AGE-AWARE standard deduction -- the same one the
        // ordinary tax was computed with -- so a 65+ filer's adder is not treated as ordinary
        // income sitting under the LTCG band.
        Optional<BigDecimal> ageAware = taxCalculator != null
                ? taxCalculator.standardDeduction(year, filingStatus) : Optional.empty();
        return ageAware.orElseGet(() -> federalTaxCalculator != null
                ? federalTaxCalculator.loadStandardDeduction(year, filingStatus)
                : BigDecimal.ZERO);
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
     * fund an ordinary/LTCG tax bill run {@link #growTraditionalGrossUp} FIRST so the traditional
     * slice this cascade is about to drain has already been grossed up and folded into the
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
        // settleTaxSaleGain when this drain IS that cycle's bill).
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
