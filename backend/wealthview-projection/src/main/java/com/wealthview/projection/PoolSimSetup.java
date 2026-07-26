package com.wealthview.projection;

/**
 * Pool balances/order the terminal or search simulation grows and withdraws from -- audit C6's
 * original extraction inside {@code GuardrailResponseBuilder}, promoted (task 12) to a shared
 * package-level type so {@link GuardrailResponseBuilder} and {@link StochasticMortalityEvaluator}
 * -- whose {@code simPools ? pool-balances : whole-portfolio-in-taxable} resolution is byte-for-
 * byte identical, both driven off a {@link PortfolioSetup} -- consume ONE implementation instead
 * of two hand-maintained copies.
 *
 * <p>{@link SustainabilitySearch} resolves pools from a (possibly {@code null}) {@link
 * TaxContext} instead, via the SEPARATE {@link #resolve(TaxContext)} factory below. That
 * resolution is deliberately NOT the same formula -- see its javadoc -- so it stays a second
 * factory rather than a shared one; unifying the two would change behavior, which task 12
 * explicitly forbids (see the task-12 report's asymmetry table).
 */
record PoolSimSetup(double initTaxable, double initTraditional, double initRoth, String order, boolean simPools) {

    /**
     * {@link GuardrailResponseBuilder}'s / {@link StochasticMortalityEvaluator}'s shared variant:
     * pools are simulated whenever a conversion schedule is present OR either pool has a positive
     * starting balance. The withdrawal order falls back to {@code "taxable_first"} both when pools
     * are off AND when pools are on but {@link PortfolioSetup#withdrawalOrder()} is {@code null}.
     */
    // UseVarargs: conversionByYear is a per-year indexed array, not a variable argument list --
    // varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    static PoolSimSetup resolve(PortfolioSetup portfolio, double[] conversionByYear) {
        boolean simPools = conversionByYear != null
                || portfolio.initTraditional() > 0 || portfolio.initRoth() > 0;
        double initTaxable = simPools ? portfolio.initTaxable() : portfolio.initialPortfolio();
        double initTraditional = simPools ? portfolio.initTraditional() : 0;
        double initRoth = simPools ? portfolio.initRoth() : 0;
        String order = simPools && portfolio.withdrawalOrder() != null
                ? portfolio.withdrawalOrder() : "taxable_first";
        return new PoolSimSetup(initTaxable, initTraditional, initRoth, order, simPools);
    }

    /**
     * {@link SustainabilitySearch}'s variant: pools are on purely off the tax context's OWN
     * balances -- a {@code null} {@code taxCtx} or zero pools means no pools. Two deliberate
     * differences from {@link #resolve(PortfolioSetup, double[])}, both pre-existing and
     * preserved (see the task-12 report): a non-null conversion schedule alone does NOT turn pools
     * on here, and the withdrawal order is NOT null-guarded when pools are on (a null {@link
     * TaxContext#withdrawalOrder()} passes straight through instead of falling back to {@code
     * "taxable_first"}).
     *
     * <p>{@code initTaxable} is a PLACEHOLDER ({@code 0}) when pools are off: the non-pool case's
     * real per-trial seed is the trial's OWN portfolio path start ({@code paths[t][0]}), which
     * varies trial-to-trial and is supplied separately at trial-config time (see {@link
     * TrialConfigFactory.Builder#initialPortfolioPaths}) -- unlike the {@link
     * #resolve(PortfolioSetup, double[])} variant, whose non-pool {@code initTaxable} is already
     * the single {@link PortfolioSetup#initialPortfolio()} scalar, fixed for every trial.
     */
    static PoolSimSetup resolve(TaxContext taxCtx) {
        boolean hasPools = taxCtx != null && (taxCtx.initTraditional() > 0 || taxCtx.initRoth() > 0);
        double initTaxable = hasPools ? taxCtx.initTaxable() : 0;
        double initTraditional = hasPools ? taxCtx.initTraditional() : 0;
        double initRoth = hasPools ? taxCtx.initRoth() : 0;
        String order = hasPools ? taxCtx.withdrawalOrder() : "taxable_first";
        return new PoolSimSetup(initTaxable, initTraditional, initRoth, order, hasPools);
    }

    /** Sum of the three pool balances. Meaningful unconditionally for the {@link
     * #resolve(PortfolioSetup, double[])} variant (its non-pool {@code initTaxable} is already the
     * real scalar); for the {@link #resolve(TaxContext)} variant it is only meaningful when {@link
     * #simPools()} -- the non-pool placeholder is {@code 0}, not the real (per-trial) balance. */
    double initTotal() {
        return initTaxable + initTraditional + initRoth;
    }
}
