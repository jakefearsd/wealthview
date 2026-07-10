package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AssetAllocationTest {

    @Test
    void ctor_unnormalizedWeights_normalizesToSumOne() {
        var alloc = new AssetAllocation(Map.of(
                AssetClass.US_STOCK, new BigDecimal("3"),
                AssetClass.BOND, new BigDecimal("1")));

        assertThat(alloc.weights().get(AssetClass.US_STOCK)).isEqualByComparingTo("0.75");
        assertThat(alloc.weights().get(AssetClass.BOND)).isEqualByComparingTo("0.25");
    }

    @Test
    void ctor_negativeWeight_throws() {
        assertThatThrownBy(() -> new AssetAllocation(Map.of(
                AssetClass.US_STOCK, new BigDecimal("-0.1"),
                AssetClass.BOND, new BigDecimal("1.1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ctor_emptyWeights_throws() {
        assertThatThrownBy(() -> new AssetAllocation(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blend_weightedReturns_computesDotProduct() {
        var alloc = AssetAllocation.fromDoubles(Map.of(
                AssetClass.US_STOCK, 0.6, AssetClass.BOND, 0.4));

        double r = alloc.blend(Map.of(AssetClass.US_STOCK, 0.10, AssetClass.BOND, 0.02));

        assertThat(r).isEqualTo(0.068, within(1e-9));
    }
}
