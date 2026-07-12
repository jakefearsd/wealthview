package com.wealthview.projection;

import java.util.Arrays;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * A deduction-netted ordinary federal tax bracket table for a single (tax year, filing status)
 * pair, replacing the old $50k-chord {@code MarginalRateCalculator} (audit C5).
 *
 * <p>Where the old precompute measured a single average marginal rate over a fixed $50,000 probe
 * -- which over/under-prices any draw whose size or bracket position differs from that probe --
 * this table reproduces {@link FederalTaxCalculator#computeTax} EXACTLY at any income point, via
 * primitive {@code double[]} arrays built once per year (outside the hot Monte Carlo trial loop)
 * from {@link FederalTaxCalculator#loadOrdinaryBrackets} and
 * {@link FederalTaxCalculator#loadStandardDeduction}. Point queries ({@link #taxAt},
 * {@link #incrementalTax}, {@link #rateAt}) are allocation-free linear scans over at most ~8
 * brackets, safe to call many times per trial per year.
 *
 * <p>The array construction deliberately mirrors {@code FederalTaxCalculator#iterateBrackets}'s
 * actual algorithm -- which fills brackets SEQUENTIALLY by list order using each bracket's own
 * WIDTH ({@code ceiling - floor}), not by treating {@code bracket.getBracketFloor()} as an
 * absolute position -- so {@link #floors} are built as a running cumulative sum of widths, not
 * copied from the entity's floor field. For the real seeded bracket data (contiguous, floor of
 * bracket i+1 == ceiling of bracket i) the two are numerically identical; building from widths
 * additionally reproduces {@code iterateBrackets}'s exact behavior for any bracket list, including
 * synthetic/non-contiguous test fixtures.
 */
final class OrdinaryTaxTable {

    /** A table with no brackets at all: {@link #taxAt} is always 0. Used when no calculator is
     * wired, reproducing {@code MarginalRateCalculator}'s old "all-zero rates" fallback exactly,
     * but as a real (non-null) table object so {@code hasPools}-style callers don't need a
     * separate null check per array element. */
    static final OrdinaryTaxTable ZERO = new OrdinaryTaxTable(0, new double[0], new double[0], new double[0]);

    private final double deduction;
    private final double[] floors;
    private final double[] rates;
    private final double[] cumTax;

    // ArrayIsStoredDirectly: this constructor is only ever called by build()/flat()/ZERO with
    // freshly-allocated arrays never mutated afterward (and never handed to a caller) -- defensive
    // copies would add per-year allocation on a path this class exists specifically to keep
    // allocation-free.
    // UseVarargs: cumTax is a bracket-indexed array, not a variable argument list.
    @SuppressWarnings({"PMD.ArrayIsStoredDirectly", "PMD.UseVarargs"})
    OrdinaryTaxTable(double deduction, double[] floors, double[] rates, double[] cumTax) {
        this.deduction = deduction;
        this.floors = floors;
        this.rates = rates;
        this.cumTax = cumTax;
    }

    /** Exact federal ordinary tax at {@code grossIncome}, reproducing {@code computeTax} to the
     * cent (subject to double-vs-BigDecimal rounding, negligible at these magnitudes). */
    double taxAt(double grossIncome) {
        if (floors.length == 0) {
            return 0;
        }
        double taxable = grossIncome - deduction;
        if (taxable <= 0) {
            return 0;
        }
        int i = bracketIndex(taxable);
        return cumTax[i] + (taxable - floors[i]) * rates[i];
    }

    /** Exact incremental tax on a {@code draw} stacked on top of {@code base}: {@code taxAt(base +
     * draw) - taxAt(base)} -- the true piecewise-linear tax on the draw, correctly pricing any
     * bracket crossing within the draw's range (unlike a flat chord rate). */
    double incrementalTax(double base, double draw) {
        if (draw <= 0 || floors.length == 0) {
            return 0;
        }
        return taxAt(base + draw) - taxAt(base);
    }

    /** The marginal rate on the next dollar at {@code grossIncome} -- 0 while still inside the
     * standard deduction. Used for the (deliberately approximate, closed-form) audit-C2 gross-up
     * rate and the deliberately-out-of-scope {@code forceRmdExcess} pricing -- NOT for pricing the
     * withdrawal-tax draw itself, which uses the exact {@link #incrementalTax}. */
    double rateAt(double grossIncome) {
        if (floors.length == 0) {
            return 0;
        }
        double taxable = grossIncome - deduction;
        // Strictly negative, unlike taxAt's <=0 guard: AT the deduction boundary (taxable == 0
        // exactly) the marginal rate on the NEXT dollar is the first bracket's rate, not 0 -- this
        // makes no difference to taxAt (the bracket walk evaluates to the same 0 there either way,
        // since the first bracket's floor is always 0 and its own cumulative tax is 0) but matters
        // here, where a caller may legitimately query the rate exactly at income == deduction (e.g.
        // a synthetic flat-rate table built with deduction 0, queried at gross income 0).
        if (taxable < 0) {
            return 0;
        }
        return rates[bracketIndex(taxable)];
    }

    private int bracketIndex(double taxable) {
        int i = 0;
        while (i < floors.length - 1 && floors[i + 1] <= taxable) {
            i++;
        }
        return i;
    }

    /** A single-bracket, no-deduction table taxing every dollar at a flat {@code rate}. By
     * linearity {@code incrementalTax}/{@code rateAt} are exact for this table at ANY base value
     * (0 works equally well as any other), so tests exercising a synthetic constant marginal rate
     * (rather than the real bracket structure) can use this instead of hand-building arrays. */
    static OrdinaryTaxTable flat(double rate) {
        return new OrdinaryTaxTable(0, new double[]{0}, new double[]{rate}, new double[]{0});
    }

    /** Builds the table for one (taxYear, status) pair. {@code age} follows
     * {@link FederalTaxCalculator#loadStandardDeduction(int, FilingStatus, int)}'s convention: any
     * value below 65 (including a negative "unknown" sentinel) reproduces the age-unaware
     * deduction. */
    static OrdinaryTaxTable build(FederalTaxCalculator taxCalculator, int taxYear, FilingStatus status, int age) {
        var brackets = taxCalculator.loadOrdinaryBrackets(taxYear, status);
        double deduction = taxCalculator.loadStandardDeduction(taxYear, status, age).doubleValue();
        int n = brackets.size();
        double[] floors = new double[n];
        double[] rates = new double[n];
        double[] cumTax = new double[n];
        double runningFloor = 0;
        double runningTax = 0;
        for (int i = 0; i < n; i++) {
            var bracket = brackets.get(i);
            floors[i] = runningFloor;
            rates[i] = bracket.rate().doubleValue();
            cumTax[i] = runningTax;
            double width = bracket.ceiling() != null
                    ? bracket.ceiling().subtract(bracket.floor()).doubleValue()
                    : Double.POSITIVE_INFINITY;
            runningFloor += width;
            runningTax += width * rates[i];
        }
        return new OrdinaryTaxTable(deduction, floors, rates, cumTax);
    }

    /** Builds one table per projection year. {@code null} {@code taxCalculator} yields
     * {@link #ZERO} for every year (matching {@code MarginalRateCalculator}'s old "no calculator ⇒
     * all-zero rates" fallback). */
    static OrdinaryTaxTable[] computeAll(@Nullable FederalTaxCalculator taxCalculator, int retirementYear,
                                          int years, FilingStatus filingStatus, @Nullable Integer birthYear) {
        OrdinaryTaxTable[] tables = new OrdinaryTaxTable[years];
        if (taxCalculator == null) {
            Arrays.fill(tables, ZERO);
            return tables;
        }
        for (int y = 0; y < years; y++) {
            int taxYear = retirementYear + y;
            int age = birthYear != null ? taxYear - birthYear : -1;
            tables[y] = build(taxCalculator, taxYear, filingStatus, age);
        }
        return tables;
    }
}
