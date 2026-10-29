package com.wealthview.projection;

import org.springframework.lang.Nullable;

/**
 * Tax funding for {@link TrialSimulator}'s per-year steps: early-withdrawal penalty, grossed-up tax drains,
 * base-income and interest tax, and long-term capital-gains tax. Static, double-based hot-loop helpers.
 */
final class TrialTaxes {

    /** Defensive cap on the audit-C2 gross-up rate: a real marginal rate cannot reach 100% (which
     * would make the T*m/(1-m) closed form diverge), and this model's brackets never approach it --
     * 50% is a generous ceiling that only ever bites on a corrupt/out-of-range input. */
    private static final double GROSS_UP_RATE_CAP = 0.50;

    /** IRC 72(t) -- 10% additional tax on early (pre-59½) traditional-account distributions, a
     * statutory constant (T18a-4). */
    private static final double EARLY_WITHDRAWAL_PENALTY_RATE = 0.10;

    /**
     * T18a-4: 10% IRC 72(t) additional tax on traditional distributions before age 59½ (the 59 1/2
     * calendar year from {@code AgeMilestones}, legacy 60 -- already used above by
     * {@code preAge595} to steer clear of traditional draws). Flat 10% on the ACTUAL dollars
     * debited from traditional for this year's spending draw ({@code traditionalDrawn} -- branch-
     * agnostic); Roth
     * conversions are OUT OF SCOPE. Funded via the simple (non-grossed-up) tax cascade -- hot-loop
     * cheap, matching the Roth-conversion-tax drain's own no-gross-up design.
     */
    static void applyEarlyWithdrawalPenalty(TrialPools tp, double traditionalDrawn, int age,
                                            int earlyAccessAge) {
        if (age < earlyAccessAge && traditionalDrawn > 0) {
            tp.deductTaxFromPools(traditionalDrawn * EARLY_WITHDRAWAL_PENALTY_RATE);
        }
    }

    /**
     * Like {@link TrialPools#deductTaxFromPools} but additionally grosses up the traditional slice
     * of the drain (audit C2): a tax payment sourced from the traditional pool is itself an ordinary-income
     * distribution and must fund its own tax. Closed-form on the geometric series T + Tm + Tm² + ...
     * = T·m/(1−m) (T = the traditional dollars this drain just pulled, capped at
     * {@link #GROSS_UP_RATE_CAP}), applied as a single additional draw directly against the
     * traditional pool -- the series itself compounds against traditional, since taxable is by
     * definition already exhausted once the base cascade reaches it.
     *
     * <p>Audit C5 coherence: {@code m} is the EXACT marginal rate at the post-draw income point
     * ({@code table.rateAt(stackedBase + T)}), read from the SAME table {@link TrialSimulator#simulateTrial}
     * uses for exact incremental pricing -- not the old flat annual chord. {@code stackedBase} is
     * each caller's view of the ordinary income already realized this year BEFORE this drain
     * (base income + actual conversion + traditional spending draw + forced RMD excess, as far as
     * the year has progressed at that call site -- C5 review fix). The closed-form T·m/(1−m)
     * structure itself is deliberately kept (an approximation the design brief explicitly permits
     * to stay), and the gross-up draws of EARLIER tax drains in the same year are not themselves
     * added to later drains' {@code stackedBase} -- that residual second-order sequencing effect
     * mirrors audit C2/T10's own "closed-form, not iterative" scope boundary. {@code table} is
     * null exactly when {@code hasPools} is false, making every call a no-op, same as the old
     * {@code marginalRate=0} behavior.
     */
    static void deductTaxFromPoolsGrossedUp(double tax, TrialPools tp,
                                              OrdinaryTaxTable table, double stackedBase) {
        double traditionalBefore = tp.traditionalTotal();
        tp.deductTaxFromPools(tax);
        double traditionalDrawn = traditionalBefore - tp.traditionalTotal();
        if (traditionalDrawn > 0 && table != null) {
            double m = Math.min(Math.max(table.rateAt(stackedBase + traditionalDrawn), 0.0), GROSS_UP_RATE_CAP);
            double grossUp = traditionalDrawn * m / (1 - m);
            // Household task 6: the gross-up drains PROPORTIONALLY across the two traditional pools,
            // capped at the available traditional total so no slot is driven negative (mirrors the
            // pre-household Math.max(0, traditional - grossUp) flooring). Single-person: all on TRAD_P.
            double tradTotal = tp.traditionalTotal();
            tp.debitPair(TrialPools.TRAD_P, TrialPools.TRAD_S, Math.min(grossUp, Math.max(0, tradTotal)));
        }
    }

    /**
     * Settles the year's base-income-tax obligation and surplus deposit (audit A4).
     * Deposits whatever surplus remains after funding the year's base income tax to taxable
     * (at cost), then drains the unfunded remainder straight from the pools via the shared
     * taxable-first cascade -- exactly like the ordinary withdrawal tax and LTCG tax: never part
     * of the spending draw. The funding draw IS grossed up when it
     * touches traditional (audit C2), at the year's full ordinary stack (C5 review fix), cross-engine
     * parity with the deterministic engine's {@code extraPoolFundedTax} treatment.
     */
    static void settleBaseIncomeTaxAndSurplus(TrialPools tp,
                                               double grossSurplus, double fundedFromSurplus,
                                               double unfundedBaseTax, OrdinaryTaxTable table,
                                               double ordinaryStack) {
        if (grossSurplus > 0) {
            double netSurplus = grossSurplus - fundedFromSurplus;
            if (netSurplus > 0) {
                tp.creditTaxable(netSurplus);
            }
        }
        if (unfundedBaseTax > 0) {
            deductTaxFromPoolsGrossedUp(unfundedBaseTax, tp, table, ordinaryStack);
        }
    }

    /**
     * Audit C1: prices and funds the tax on this year's ordinary-interest income, exactly (audit
     * C5) via {@code table.incrementalTax(base, interestIncome)} -- interest is the FIRST ordinary
     * income stacked directly on the year's base (realized as soon as growth runs, before RMD/
     * conversion/the spending draw). Like the RMD/withdrawal tax it is drained from the pools,
     * grossed up when it touches traditional (audit C2). A {@code null} table (no tax modeling this
     * trial) or non-positive interest income leaves the pools untouched.
     */
    static void applyInterestTax(TrialPools tp, double interestIncome,
                                  OrdinaryTaxTable table, double base) {
        if (table == null || interestIncome <= 0) {
            return;
        }
        double interestTax = table.incrementalTax(base, interestIncome);
        if (interestTax > 0) {
            deductTaxFromPoolsGrossedUp(interestTax, tp, table, base);
        }
    }

    /**
     * Applies the long-term capital-gains tax on this year's realized spending gain plus qualified
     * dividend plus (D5) the accumulated gain of every tax-funding sale -- see
     * {@link #ltcgTaxForYear} -- exactly (audit C5) via {@code ltcgTable}, evaluated at the ACTUAL full ordinary
     * stack for this trial-year ({@code ordinaryStack} = base income + actual capped Roth
     * conversion + actual traditional spending draw + forced RMD excess) -- fixing the old
     * {@code LtcgRateCalculator}'s omission of every same-year draw from the floor it probed,
     * which could silently under-price a gain those draws push across the 0%/15% boundary
     * (C5 review fix: the floor now also includes the RMD excess and uses the CAPPED actual
     * conversion, not the pre-cap target).
     *
     * <p>Like the RMD/withdrawal tax it is an additional outflow drained taxable-first from the
     * pools (retirement-scoped; the MC path is retirement-only), grossed up when it touches
     * traditional (audit C2) -- using the ORDINARY table at the same stacked point for the
     * gross-up rate, not the LTCG rate: the traditional draw funding this bill is itself ordinary
     * income once withdrawn, regardless of what kind of tax it is paying. A {@code null} table (no
     * capital-gains calculator wired) or a non-positive net LTCG income leaves the pools
     * untouched. {@code netRentalIncome} (T18a-3) joins the NIIT Net Investment Income base only
     * (never the LTCG bracket tax) -- see {@link LtcgTaxTable#taxAt(double, double, double)}.
     */
    static void applyLtcgTax(TrialPools tp, double realizedGain,
                              double dividendIncome, LtcgTaxTable ltcgTable,
                              double ordinaryStack, OrdinaryTaxTable ordinaryTable,
                              double netRentalIncome) {
        double ltcgTax = ltcgTaxForYear(tp, realizedGain, dividendIncome, ltcgTable, ordinaryStack,
                netRentalIncome);
        if (ltcgTax > 0) {
            deductTaxFromPoolsGrossedUp(ltcgTax, tp, ordinaryTable, ordinaryStack);
        }
        // The LTCG bill's own funding sale was priced inside ltcgTaxForYear; discard what that drain
        // just accumulated so it is not carried into next year.
        tp.drainTaxSaleGain();
    }

    /**
     * D5: this trial-year's LTCG tax on the spending sale's {@code realizedGain} + qualified
     * {@code dividendIncome} + every non-spending taxable sale's accumulated gain (drained from
     * {@code tp}; a net loss floors LTCG income at zero), INCLUDING the gain of the sale that will
     * fund this bill -- closed form {@code sale = tax / (1 - r)} from one measured chord {@code r}, with
     * the tax re-priced at the jump's gain (no further polish passes: the hot-loop budget; the
     * deterministic engine polishes up to 3 times). A
     * {@code null} table returns 0 (the accumulator is still drained).
     */
    static double ltcgTaxForYear(TrialPools tp, double realizedGain, double dividendIncome,
                                 @Nullable LtcgTaxTable ltcgTable, double ordinaryStack, double netRentalIncome) {
        double ltcgIncome = Math.max(0, realizedGain + tp.drainTaxSaleGain() + dividendIncome);
        if (ltcgTable == null || ltcgIncome <= 0) {
            return 0;
        }
        double stack = Math.max(0, ordinaryStack);
        double tax = ltcgTable.taxAt(stack, ltcgIncome, netRentalIncome);
        double taxableAvail = Math.max(0, tp.taxable());
        double sale0 = Math.min(tax, taxableAvail);
        if (sale0 <= 0) {
            return tax;
        }
        double gain0 = tp.peekTaxableSaleGain(sale0);
        if (gain0 <= 0) {
            return tax;
        }
        double taxPerSaleDollar = (ltcgTable.taxAt(stack, ltcgIncome + gain0, netRentalIncome) - tax) / sale0;
        double warmSale = Math.min(tax / (1 - Math.min(Math.max(taxPerSaleDollar, 0), GROSS_UP_RATE_CAP)),
                taxableAvail);
        return ltcgTable.taxAt(stack, ltcgIncome + tp.peekTaxableSaleGain(warmSale), netRentalIncome);
    }

    private TrialTaxes() {
    }
}
