package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

public interface StateTaxCalculator {

    BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status);

    BigDecimal getStandardDeduction(int taxYear, FilingStatus status);

    String stateCode();

    boolean taxesCapitalGainsAsOrdinaryIncome();

    /**
     * Whether this state levies an income tax at all. {@code false} only for
     * {@link NullStateTaxCalculator}, the null object used when a scenario has no state tax. Lets
     * callers branch on the capability rather than on the concrete implementation type, so a future
     * second no-tax implementation gets the same treatment for free.
     */
    default boolean hasStateTax() {
        return true;
    }

    /**
     * Whether this state fully exempts Social Security benefits from its own taxable base, so the
     * {@code CombinedTaxCalculator} seam should subtract the year's federally-taxed Social Security
     * amount from the state base before computing state tax (audit C3). Defaults to {@code true} --
     * the three currently supported states (CA, AZ, OR) all fully exempt Social Security -- so a
     * future state that taxes some or all of it (e.g. CO, CT, MN, MT, NE, NM, RI, UT, VT, WV) can
     * override this to {@code false} without touching the existing trio.
     */
    default boolean exemptsSocialSecurity() {
        return true;
    }
}
