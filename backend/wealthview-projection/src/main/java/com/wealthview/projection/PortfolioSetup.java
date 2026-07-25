package com.wealthview.projection;

/** Portfolio configuration and pool balances passed into the optimizer. */
record PortfolioSetup(
        double initTaxable, double initTraditional, double initRoth,
        double initialPortfolio, String withdrawalOrder,
        int cashReserveYears, double cashReturnRate,
        double terminalTarget, double portfolioFloor,
        double initTaxableBasis
) {

    /** All balances and rates zero, no withdrawal order — for a run with no years to simulate. */
    static PortfolioSetup empty() {
        return new PortfolioSetup(0, 0, 0, 0, null, 0, 0, 0, 0, 0);
    }
}
