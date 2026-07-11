package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

public class CombinedTaxCalculator implements TaxCalculationStrategy {

    // Real-terms note: both SALT cap tiers below are statutorily fixed nominal, so in today's-dollars
    // terms they erode over the projection horizon. Unlike the SS thresholds they are NOT deflated
    // here — doing so would require threading yearsFromBase/inflation through the whole
    // TaxCalculationStrategy interface for a second-order effect. They are held constant nominal (a
    // documented, minor simplification).
    //
    // OBBBA (One Big Beautiful Bill Act, H.R. 1, enacted 2025) raised the SALT cap to $40,000 for tax
    // years 2025-2029 (statutorily reverting to the pre-OBBBA $10,000 cap starting 2030). OBBBA also
    // indexes the $40,000 figure up ~1%/year for 2026-2029 and phases it down for MAGI above $500k
    // (fully phased back to $10,000 at $600k) -- both nuances are intentionally NOT modeled here (out
    // of this fix's scope, per the audit item); the cap is held flat at $40,000 for the whole
    // 2025-2029 window regardless of income.
    private static final BigDecimal SALT_CAP_OBBBA = new BigDecimal("40000");
    private static final BigDecimal SALT_CAP_BASE = new BigDecimal("10000");
    private static final int OBBBA_SALT_CAP_FIRST_YEAR = 2025;
    private static final int OBBBA_SALT_CAP_LAST_YEAR = 2029;

    private final FederalTaxCalculator federal;
    private final StateTaxCalculator state;
    private final BigDecimal primaryResidencePropertyTax;
    private final BigDecimal primaryResidenceMortgageInterest;
    /**
     * The primary filer's birth year, used to derive their age for the year-aware standard
     * deduction (audit D age-65+ addition). {@code null} when the caller doesn't track it, in which
     * case this calculator falls back to the age-less {@link FederalTaxCalculator} overloads --
     * identical to its pre-age65-feature behavior. This engine only tracks the PRIMARY filer's
     * birth year, so an MFJ household where both spouses are 65+ still gets only ONE age-65 adder;
     * see {@link FederalTaxCalculator#loadStandardDeduction(int, FilingStatus, int)}.
     */
    @Nullable
    private final Integer birthYear;

    public CombinedTaxCalculator(FederalTaxCalculator federal,
                                  StateTaxCalculator state,
                                  BigDecimal primaryResidencePropertyTax,
                                  BigDecimal primaryResidenceMortgageInterest) {
        this(federal, state, primaryResidencePropertyTax, primaryResidenceMortgageInterest, null);
    }

    public CombinedTaxCalculator(FederalTaxCalculator federal,
                                  StateTaxCalculator state,
                                  BigDecimal primaryResidencePropertyTax,
                                  BigDecimal primaryResidenceMortgageInterest,
                                  @Nullable Integer birthYear) {
        this.federal = federal;
        this.state = state;
        this.primaryResidencePropertyTax = primaryResidencePropertyTax != null
                ? primaryResidencePropertyTax : BigDecimal.ZERO;
        this.primaryResidenceMortgageInterest = primaryResidenceMortgageInterest != null
                ? primaryResidenceMortgageInterest : BigDecimal.ZERO;
        this.birthYear = birthYear;
    }

    /**
     * The SALT cap for the given tax year (audit D): $40,000 for 2025-2029 (OBBBA), $10,000
     * otherwise (pre-OBBBA law, and the post-sunset law from 2030 on).
     */
    private static BigDecimal saltCap(int taxYear) {
        return taxYear >= OBBBA_SALT_CAP_FIRST_YEAR && taxYear <= OBBBA_SALT_CAP_LAST_YEAR
                ? SALT_CAP_OBBBA : SALT_CAP_BASE;
    }

    /**
     * The federal standard deduction for (taxYear, status), boosted by the age-65+ addition when
     * {@link #birthYear} is known and the primary filer turns 65+ in {@code taxYear} (audit D).
     */
    private BigDecimal resolveFederalStandardDeduction(int taxYear, FilingStatus status) {
        return birthYear != null
                ? federal.loadStandardDeduction(taxYear, status, taxYear - birthYear)
                : federal.loadStandardDeduction(taxYear, status);
    }

    public CombinedTaxResult computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return computeTax(grossIncome, taxYear, status, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Like {@link #computeTax(BigDecimal, int, FilingStatus)} but lets the STATE base diverge from
     * the federal base (audit C3): {@code ltcgIncome} (the year's realized long-term capital gains +
     * qualified dividends -- LTCG is federal-only elsewhere in this engine, taxed separately via
     * {@code CapitalGainsTaxCalculator}) is added to the state base when
     * {@link StateTaxCalculator#taxesCapitalGainsAsOrdinaryIncome()}, and
     * {@code federallyTaxedSocialSecurity} (the year's federally-taxable Social Security portion,
     * already folded into {@code grossIncome}) is subtracted from the state base when
     * {@link StateTaxCalculator#exemptsSocialSecurity()}. The federal computation is untouched by
     * either figure -- both remain the pre-existing federal treatment (LTCG taxed separately; SS
     * taxed via the federal worksheet's own inclusion, already baked into {@code grossIncome}).
     *
     * <p><b>Callers:</b> both the pool-FUNDING computations ({@code PoolStrategy.MultiPool}'s
     * withdrawal/conversion tax bundles and {@code RetirementWithdrawalProcessor}'s surplus branch)
     * and the DISPLAYED breakdown recompute ({@code RetirementTaxAnnotator}) call this overload with
     * the same per-year figures, so the state adjustment both drains the pools and shows in the
     * federal/state breakdown -- {@code federalTax + stateTax} reconciles with {@code taxLiability}.
     * Convention: the year's realized LTCG/dividend income enters exactly ONE bundle (the withdrawal
     * bundle, and the annotator's whole-year recompute); conversion/surplus base bundles pass zero
     * LTCG so the marginal base-tax subtractions net exactly and stay monotone (full bundle income
     * is a superset of every base bundle's, with an identical SS subtraction).
     */
    public CombinedTaxResult computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status,
                                         BigDecimal ltcgIncome, BigDecimal federallyTaxedSocialSecurity) {
        BigDecimal stateBase = resolveStateBase(grossIncome, ltcgIncome, federallyTaxedSocialSecurity);

        if (grossIncome.compareTo(BigDecimal.ZERO) <= 0 && stateBase.compareTo(BigDecimal.ZERO) <= 0) {
            return CombinedTaxResult.ZERO;
        }

        // 1. Compute state tax on the state base (state applies its own deduction internally)
        BigDecimal stateTax = state.computeTax(stateBase, taxYear, status);

        // 2. SALT = min(state_tax + property_tax, year-aware cap -- audit D)
        BigDecimal saltUncapped = stateTax.add(primaryResidencePropertyTax);
        BigDecimal salt = saltUncapped.min(saltCap(taxYear));

        // 3. Itemized deductions = SALT + mortgage interest
        BigDecimal itemized = salt.add(primaryResidenceMortgageInterest);

        // 4. Compare itemized vs federal standard deduction
        BigDecimal federalStandardDeduction = resolveFederalStandardDeduction(taxYear, status);
        boolean useItemized = itemized.compareTo(federalStandardDeduction) > 0;
        BigDecimal chosenDeduction = useItemized ? itemized : federalStandardDeduction;

        // 5. Compute federal tax using chosen deduction, on the UNADJUSTED gross income -- LTCG and
        // Social Security both keep their existing federal treatment regardless of state rules.
        BigDecimal federalTax = grossIncome.compareTo(BigDecimal.ZERO) > 0
                ? federal.computeTaxWithDeduction(grossIncome, chosenDeduction, taxYear, status)
                : BigDecimal.ZERO;

        BigDecimal totalTax = federalTax.add(stateTax);

        return new CombinedTaxResult(federalTax, stateTax, totalTax, salt, itemized, useItemized);
    }

    /**
     * The state's own taxable base: gross ordinary income, plus realized LTCG/dividend income for
     * states that tax capital gains as ordinary income, minus the federally-taxed Social Security
     * amount for states that fully exempt Social Security. Floored at zero -- a state base can't go
     * negative even when the SS exemption exceeds the (gross + LTCG) figure.
     */
    private BigDecimal resolveStateBase(BigDecimal grossIncome, BigDecimal ltcgIncome,
                                         BigDecimal federallyTaxedSocialSecurity) {
        BigDecimal base = grossIncome;
        if (state.taxesCapitalGainsAsOrdinaryIncome() && ltcgIncome != null) {
            base = base.add(ltcgIncome);
        }
        if (state.exemptsSocialSecurity() && federallyTaxedSocialSecurity != null) {
            base = base.subtract(federallyTaxedSocialSecurity);
        }
        return base.max(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal computeTotalTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return computeTax(grossIncome, taxYear, status).totalTax();
    }

    @Override
    public CombinedTaxResult computeDetailedTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return computeTax(grossIncome, taxYear, status);
    }

    @Override
    public CombinedTaxResult computeDetailedTax(BigDecimal grossIncome, int taxYear, FilingStatus status,
                                                 BigDecimal ltcgIncome, BigDecimal federallyTaxedSocialSecurity) {
        return computeTax(grossIncome, taxYear, status, ltcgIncome, federallyTaxedSocialSecurity);
    }

    @Override
    public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int taxYear, FilingStatus status) {
        // The target rate represents a federal bracket rate (from the UI dropdown).
        // Find the federal bracket ceiling and add the appropriate deduction
        // (standard or itemized, whichever the filer would use at that income level).
        // State tax is a cost of the conversion, not a gating factor for how much to convert.

        if (state instanceof NullStateTaxCalculator) {
            return federal.computeMaxIncomeForBracket(targetRate, taxYear, status);
        }

        BigDecimal bracketCeiling = federal.findBracketCeiling(targetRate, taxYear, status);
        if (bracketCeiling.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        // Determine whether itemized or standard deduction applies at the bracket ceiling
        BigDecimal standardDeduction = federal.loadStandardDeduction(taxYear, status);
        BigDecimal grossEstimate = bracketCeiling.add(standardDeduction);

        BigDecimal estStateTax = state.computeTax(grossEstimate, taxYear, status);
        BigDecimal estSalt = estStateTax.add(primaryResidencePropertyTax).min(saltCap(taxYear));
        BigDecimal estItemized = estSalt.add(primaryResidenceMortgageInterest);
        BigDecimal chosenDeduction = estItemized.compareTo(standardDeduction) > 0
                ? estItemized : standardDeduction;

        return bracketCeiling.add(chosenDeduction);
    }
}
