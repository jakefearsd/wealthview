package com.wealthview.core.projection.dto;

import java.util.Map;

import org.springframework.lang.Nullable;

/**
 * One year of real returns for a crash replay: per asset class and per bond maturity.
 *
 * @param historyYear the historical year these returns come from, or {@code null} for a long-run average year
 */
public record YearReturns(@Nullable Integer historyYear, Map<AssetClass, Double> assets,
                          Map<BondMaturity, Double> maturities) {

    public YearReturns {
        assets = Map.copyOf(assets);
        maturities = Map.copyOf(maturities);
    }
}
