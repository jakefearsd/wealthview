package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

import static com.wealthview.projection.PoolStrategy.ROUNDING;
import static com.wealthview.projection.PoolStrategy.SCALE;

/**
 * Solves the year's withdrawal-cycle tax bill: the ordinary-income bundle, the LTCG stack, the
 * audit-C2 traditional gross-up fixed point, the D5 tax-funding-sale gain fixed point and the
 * 10% early-withdrawal penalty. Extracted from {@link MultiPool}; it is a pure function of its
 * collaborators and the {@link BillInputs} (it never mutates a pool). Each {@link #settle} call
 * runs in its own {@link BillRun}, so the solver holds no mutable state; the last ordinary-tax
 * breakdown recorded during the call is returned on the {@link TaxBill} for the caller to keep.
 */
final class WithdrawalTaxBillSolver {
    /**
     * Maximum polish passes for the audit-C2 traditional-tax gross-up AFTER the closed-form
     * warm start (see {@link BillRun#growTraditionalGrossUp}). Raised 5 -> 10 (T10 review): plain
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
     * {@link BillRun#growTraditionalGrossUp}: a real combined federal+state marginal rate cannot reach
     * 100% (which would make the {@code implied/(1-m)} closed form diverge); 50% comfortably
     * covers the worst realistic combination (37% federal + 13.3% CA). The cap only shortens
     * the JUMP -- the polish loop still converges to the true fixed point afterwards.
     */
    private static final BigDecimal GROSS_UP_WARM_START_RATE_CAP = new BigDecimal("0.50");
    /** D5: polish passes after the closed-form warm start in {@link BillRun#settleTaxSaleGain}. */
    private static final int MAX_TAX_SALE_GAIN_ITERATIONS = 3;
    /** IRC 72(t) -- 10% additional tax on early (pre-59½) traditional-account distributions,
     * a statutory constant (T18a-4). */
    private static final BigDecimal EARLY_WITHDRAWAL_PENALTY_RATE = new BigDecimal("0.10");

    private final TaxCalculationStrategy taxCalculator;
    // Capital-gains taxation collaborators (null calculator => taxable pool stays untaxed,
    // preserving pre-lots behavior for callers that don't wire capital gains).
    private final CapitalGainsTaxCalculator capitalGainsTaxCalculator;
    /**
     * Source of the federal standard deduction, used ONLY to net the LTCG stacking floor down
     * to the same base the ordinary tax computed on (see {@link BillRun#resolveOrdinaryDeduction}). Null
     * for callers that don't wire it, in which case the floor stays gross (pre-fix behavior).
     */
    private final FederalTaxCalculator federalTaxCalculator;
    private final int baseYear;
    private final BigDecimal inflationRate;
    private final int earlyAccessAge;

    WithdrawalTaxBillSolver(@Nullable TaxCalculationStrategy taxCalculator,
                            @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                            @Nullable FederalTaxCalculator federalTaxCalculator,
                            int baseYear, BigDecimal inflationRate, int earlyAccessAge) {
        this.taxCalculator = taxCalculator;
        this.capitalGainsTaxCalculator = capitalGainsTaxCalculator;
        this.federalTaxCalculator = federalTaxCalculator;
        this.baseYear = baseYear;
        this.inflationRate = inflationRate;
        this.earlyAccessAge = earlyAccessAge;
    }

    /** D5: everything the year's withdrawal-cycle bill depends on EXCEPT the gain of the taxable
     * sale that funds it. {@code taxableAvail}/{@code traditionalAvail} are frozen post-spend-draw,
     * pre-drain snapshots (the C2 gross-up contract). {@code filingStatus},
     * {@code qualifiedDividendIncome} and {@code ordinaryInterestIncome} are the pool's values at the
     * moment the bill is settled. */
    record BillInputs(int year, BigDecimal effectiveOtherIncome, BigDecimal conversionAmount,
                      BigDecimal traditionalSpendIncome, BigDecimal baseRealizedGain,
                      BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax,
                      BigDecimal federallyTaxedSocialSecurity, BigDecimal netRentalIncome, int age,
                      BigDecimal taxableAvail, BigDecimal traditionalAvail, FilingStatus filingStatus,
                      BigDecimal qualifiedDividendIncome, BigDecimal ordinaryInterestIncome) {}

    /** D5: one evaluation of the year's full bill (ordinary bundle + LTCG incl. the gross-up
     * re-stack + C2 gross-up + the 10% early-withdrawal penalty) at a candidate tax-funding-sale
     * gain. {@code ltcgTax} already includes the re-stack delta. {@code recordedBreakdown} is the
     * last breakdown recorded by any ordinary-tax evaluation during the {@code settle} call, or
     * empty if none was recorded. */
    record TaxBill(BigDecimal total, BigDecimal ltcgTax, BigDecimal realizedLtcgIncome,
                   BigDecimal traditionalGrossUp, BigDecimal earlyWithdrawalPenalty,
                   Optional<CombinedTaxResult> recordedBreakdown) {}

    /**
     * D5: settles the year's bill, including the fixed-point gain of the taxable sale that funds
     * it. {@code fifoGainPeek} returns the FIFO gain a hypothetical sale of the given size would
     * realize and must not mutate the pool.
     */
    TaxBill settle(BillInputs in, UnaryOperator<BigDecimal> fifoGainPeek) {
        var run = new BillRun(in, fifoGainPeek);
        var settled = run.settleTaxSaleGain();
        return new TaxBill(settled.total(), settled.ltcgTax(), settled.realizedLtcgIncome(),
                settled.traditionalGrossUp(), settled.earlyWithdrawalPenalty(),
                Optional.ofNullable(run.recorded));
    }

    /** The year's ordinary-income tax bundle: the net pool-funded tax plus the detailed result. */
    private record OrdinaryTax(BigDecimal tax, @Nullable CombinedTaxResult detailed) {
        static final OrdinaryTax NONE = new OrdinaryTax(BigDecimal.ZERO, null);
    }


    /**
     * The converged audit-C2 gross-up: how much EXTRA traditional income the fixed point below
     * settled on, and the resulting total bill. {@code recorded} is NOT threaded through
     * here -- {@code computeOrdinaryTax} already updates it as a side effect on every call it
     * makes (including the search's last one), with its own conditional-recording logic, so the
     * field is left exactly as that method's last call set it.
     */
    private record GrossUpResult(BigDecimal traditionalGrossUp, BigDecimal tax) {}

    /**
     * One {@link #settle} call's working state: the inputs, the gain-peek seam and the last
     * breakdown any ordinary-tax evaluation recorded (the old {@code lastTaxBreakdown} side effect,
     * now scoped to the call).
     */
    private final class BillRun {
        private final BillInputs in;
        private final UnaryOperator<BigDecimal> fifoGainPeek;
        @Nullable
        private CombinedTaxResult recorded;

        BillRun(BillInputs in, UnaryOperator<BigDecimal> fifoGainPeek) {
            this.in = in;
            this.fifoGainPeek = fifoGainPeek;
        }

        private TaxBill computeBill(BigDecimal taxSaleGain) {
            BigDecimal realizedGain = in.baseRealizedGain().add(taxSaleGain);
            // Realized LTCG + qualified-dividend INCOME, floored at zero: a net realized loss offsets
            // the year's gains/dividends but never yields a negative tax (its small AGI offset stays
            // out of scope). Computed BEFORE the ordinary-tax bundle because it feeds two places: the
            // state base of that bundle for states that tax capital gains as ordinary income (audit
            // C3), and AGI-ex-SS for the Social Security provisional-income convergence (audit B2).
            BigDecimal realizedLtcgIncome = realizedGain.add(in.qualifiedDividendIncome()).max(BigDecimal.ZERO);

            // Audit C1: this year's ordinary-interest income (the taxable pool's bond+cash sleeve,
            // booked in applyGrowth) joins the ORDINARY bundle directly -- unlike qualifiedDividend/
            // realizedGain, it is NOT LTCG income, so it does NOT flow through realizedLtcgIncome
            // above (no LTCG-rate treatment, no LTCG-bracket floor stacking). It DOES flow through
            // the same ordinary path effectiveOtherIncome/conversionAmount/traditionalOrdinaryIncome
            // already take: the full taxableIncome bundle below (which feeds the Social Security
            // provisional-income convergence via realizedPortfolioTaxable -- see
            // YearFinanceResolver -- and the state base for capital-gains-as-ordinary states).
            BigDecimal taxableIncome = in.traditionalSpendIncome().add(in.effectiveOtherIncome())
                    .add(in.conversionAmount()).add(in.ordinaryInterestIncome());
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
            // deliberately NOT added to recorded here: for retired years RetirementTaxAnnotator
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
            // ALREADY updates recorded as a side effect (with its own conditional-recording
            // logic -- see that method's javadoc) on every call it makes, including the LAST one when
            // the loop actually iterates. Nothing further needs to happen here: when the loop doesn't
            // iterate (no traditional slice to gross up), recorded is untouched by this method
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
                    grossUp.traditionalGrossUp(), penalty, Optional.empty());
        }

        /**
         * D5: solves {@code bill = Bill(gainOf(min(bill, taxableAvail)))} -- the taxable sale that pays
         * the year's bill realizes a gain, which raises the bill. Same convergence shape as
         * {@link #growTraditionalGrossUp}: one naive pass, a closed-form warm jump off the measured
         * tax-per-sale-dollar chord ({@code sale = bill0 / (1 - r)}), then at most
         * {@link #MAX_TAX_SALE_GAIN_ITERATIONS} polish passes to a $1 gain tolerance. Never mutates a
         * pool ({@code fifoGainPeek} only); the LAST {@code computeBill} call is always at the returned
         * gain, so {@code recorded} reflects the settled bill. No embedded gain => returns the
         * gain-0 bill, byte-identical to pre-D5.
         */
        private TaxBill settleTaxSaleGain() {
            TaxBill bill = computeBill(BigDecimal.ZERO);
            BigDecimal sale0 = bill.total().min(in.taxableAvail());
            BigDecimal gain = fifoGainPeek.apply(sale0);
            if (gain.abs().compareTo(GROSS_UP_TOLERANCE) < 0) {
                return bill;
            }
            TaxBill atGain = computeBill(gain);
            BigDecimal taxPerSaleDollar = atGain.total().subtract(bill.total())
                    .divide(sale0, SCALE + 4, ROUNDING)
                    .max(BigDecimal.ZERO)
                    .min(GROSS_UP_WARM_START_RATE_CAP);
            BigDecimal warmSale = bill.total()
                    .divide(BigDecimal.ONE.subtract(taxPerSaleDollar), SCALE + 4, ROUNDING)
                    .min(in.taxableAvail());
            BigDecimal settledGain = fifoGainPeek.apply(warmSale);
            TaxBill current = computeBill(settledGain);
            for (int i = 0; i < MAX_TAX_SALE_GAIN_ITERATIONS; i++) {
                BigDecimal impliedGain = fifoGainPeek.apply(current.total().min(in.taxableAvail()));
                if (impliedGain.subtract(settledGain).abs().compareTo(GROSS_UP_TOLERANCE) < 0) {
                    break;
                }
                settledGain = impliedGain;
                current = computeBill(settledGain);
            }
            return current;
        }

        /**
         * Computes the withdrawal cycle's ordinary-income tax bundle and records it in
         * {@code recorded}. The state base of this bundle adds the year's realized
         * LTCG/dividend income (for states taxing capital gains as ordinary income) and exempts the
         * federally-taxed Social Security amount (for states that exempt it) -- audit C3; the
         * DISPLAYED breakdown ({@code RetirementTaxAnnotator}) recomputes with the SAME inputs, so
         * display and pool funding agree.
         *
         * <p>The {@code realizedLtcgIncome > 0} guard leg covers a retiree with ZERO ordinary income
         * but realized gains/dividends: a state that taxes capital gains as ordinary income still
         * owes state tax on them. For every other strategy that extra computation is all-zero and
         * {@code recorded} stays empty (see the recording condition below), preserving
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

            var detailed = taxCalculator.computeDetailedTax(taxableIncome, year, in.filingStatus(),
                    realizedLtcgIncome, federallyTaxedSocialSecurity);

            BigDecimal tax;
            if (conversionAmount.compareTo(BigDecimal.ZERO) > 0) {
                var baseTax = taxCalculator.computeDetailedTax(
                        conversionAmount.add(effectiveOtherIncome), year, in.filingStatus(),
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
                recorded = detailed;
            }
            return new OrdinaryTax(tax, detailed);
        }

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
         * MultiPool#deductFromPools} call on the returned {@code tax} -- this method never mutates a pool.
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
         * {@code recorded} via {@code computeOrdinaryTax}'s own recording side effect. */
        private BigDecimal recomputeBill(BigDecimal baseTaxableIncome, BigDecimal slice, int year,
                BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, BigDecimal alreadyChargedBaseTax,
                BigDecimal realizedLtcgIncome, BigDecimal federallyTaxedSocialSecurity,
                BigDecimal constantTax) {
            var recomputed = computeOrdinaryTax(baseTaxableIncome.add(slice), year, effectiveOtherIncome,
                    conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome, federallyTaxedSocialSecurity);
            return recomputed.tax().add(constantTax);
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
            BigDecimal ltcgIncome = realizedGain.add(in.qualifiedDividendIncome());
            if (ltcgIncome.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal magi = ordinaryTaxableIncome.add(ltcgIncome);
            BigDecimal deduction = resolveOrdinaryDeduction(ordinaryTaxDetail, year);
            BigDecimal ordinaryForLtcg = ordinaryTaxableIncome.subtract(deduction).max(BigDecimal.ZERO);
            // T18a-3: net rental income joins the NIIT's Net Investment Income base (NOT the LTCG
            // bracket-tax base) -- see CapitalGainsTaxCalculator's rental overload.
            return capitalGainsTaxCalculator.computeLtcgTax(ordinaryForLtcg, ltcgIncome, year,
                    in.filingStatus(), year - baseYear, inflationRate, magi, netRentalIncome);
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
                    ? taxCalculator.standardDeduction(year, in.filingStatus()) : Optional.empty();
            return ageAware.orElseGet(() -> federalTaxCalculator != null
                    ? federalTaxCalculator.loadStandardDeduction(year, in.filingStatus())
                    : BigDecimal.ZERO);
        }
    }

    /**
     * The traditional-sourced dollars of a hypothetical {@code tax} payment cascading
     * taxable-first against FROZEN (pre-drain) pool balances -- mirrors {@link
     * MultiPool#deductFromPools}'s cascade order without mutating anything, so {@link
     * BillRun#growTraditionalGrossUp}'s fixed-point search can explore candidate bills before the real
     * drain happens.
     */
    private static BigDecimal peekTraditionalSlice(BigDecimal tax, BigDecimal taxableAvail,
                                                    BigDecimal traditionalAvail) {
        BigDecimal afterTaxable = tax.subtract(taxableAvail).max(BigDecimal.ZERO);
        return afterTaxable.min(traditionalAvail).max(BigDecimal.ZERO);
    }
}
