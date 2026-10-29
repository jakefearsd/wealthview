package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;

import static org.assertj.core.api.Assertions.assertThat;

class TentSetupTest {

    @Test
    void constructor_sourceCollectionsMutatedLater_recordIsUnchanged() {
        var order = new ArrayList<>(List.of(PoolType.TAXABLE));
        var shares = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        shares.put(BondMaturity.LONG, BigDecimal.ONE);
        var real = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        real.put(BondMaturity.LONG, new BigDecimal("0.02"));
        var nominal = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        nominal.put(BondMaturity.LONG, new BigDecimal("0.04"));

        var setup = new PoolStrategy.TentSetup(order, shares, real, nominal);
        order.add(PoolType.ROTH);
        shares.put(BondMaturity.SHORT, BigDecimal.TEN);
        real.clear();
        nominal.put(BondMaturity.SHORT, BigDecimal.TEN);

        assertThat(setup.placementOrder()).containsExactly(PoolType.TAXABLE);
        assertThat(setup.mixShares()).containsOnlyKeys(BondMaturity.LONG);
        assertThat(setup.realReturns()).containsEntry(BondMaturity.LONG, new BigDecimal("0.02"));
        assertThat(setup.nominalYields()).containsOnlyKeys(BondMaturity.LONG);
    }

    @Test
    void accessors_returnedCollections_areImmutable() {
        var setup = new PoolStrategy.TentSetup(List.of(PoolType.TAXABLE), Map.of(), Map.of(), Map.of());

        assertThat(setup.placementOrder()).isUnmodifiable();
        assertThat(setup.mixShares()).isUnmodifiable();
    }
}
