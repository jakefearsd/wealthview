package com.wealthview.projection;

/** Portfolio configuration and pool balances passed into the optimizer. */
record PortfolioSetup(
        double initTaxable, double initTraditional, double initRoth,
        double initialPortfolio, String withdrawalOrder,
        int cashReserveYears, double cashReturnRate,
        double terminalTarget, double portfolioFloor
) {}
