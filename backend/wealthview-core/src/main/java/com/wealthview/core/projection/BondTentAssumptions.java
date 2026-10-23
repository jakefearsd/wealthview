package com.wealthview.core.projection;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.wealthview.core.projection.dto.BondMaturity;

/**
 * The historical real geometric mean return per bond maturity that the deterministic projection grows the
 * tent's sleeves at: the 1972-2025 window, the same constant-average convention as every other asset there.
 */
@Component
public class BondTentAssumptions {

    private final CapitalMarketAssumptionsProvider capitalMarket;
    private final BondMaturityReturnsProvider maturityReturns;
    /**
     * Computed lazily from immutable seed data, so every thread derives the same value and a racing
     * recomputation is harmless; volatile publishes the finished map safely.
     */
    private volatile Map<BondMaturity, Double> cached;

    public BondTentAssumptions(CapitalMarketAssumptionsProvider capitalMarket,
                               BondMaturityReturnsProvider maturityReturns) {
        this.capitalMarket = capitalMarket;
        this.maturityReturns = maturityReturns;
    }

    /** Real geometric mean return per maturity, cached after the first call. */
    public Map<BondMaturity, Double> realReturns() {
        var result = cached;
        if (result == null) {
            var matrix = maturityReturns.alignedTo(capitalMarket.matrix(false));
            result = Map.copyOf(new EnumMap<>(BondMaturityReturnsProvider.geometricMeansOf(matrix)));
            cached = result;
        }
        return result;
    }
}
