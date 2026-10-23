package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The sizing of a scenario's bond tent: the portfolio the projection reaches at retirement, the coverage in years,
 * one sizing per configured mix (the plan mix first) and the retired years that drive the size.
 */
public record BondTentSizing(
        BigDecimal projectedPortfolioAtRetirement,
        BigDecimal coverageYears,
        List<BondTentMixSizing> mixes,
        List<BondTentCoveredYear> coveredYears) {
}
