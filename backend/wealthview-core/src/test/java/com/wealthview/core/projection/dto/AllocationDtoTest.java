package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationDtoTest {

    @Test
    void toWeightMap_percentages_keyedByAssetClassKey() {
        var dto = new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
                new BigDecimal("15"), new BigDecimal("5"));

        var map = dto.toWeightMap();

        assertThat(map).containsEntry("us_stock", new BigDecimal("60"))
                .containsEntry("intl_stock", new BigDecimal("20"))
                .containsEntry("bond", new BigDecimal("15"))
                .containsEntry("cash", new BigDecimal("5"));
    }

    @Test
    void validate_sumNot100_throws() {
        var dto = new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
                new BigDecimal("15"), new BigDecimal("10"));

        assertThatThrownBy(dto::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void fromAllocation_normalizedFractions_toPercentages() {
        var alloc = AssetAllocation.fromDoubles(java.util.Map.of(
                AssetClass.US_STOCK, 0.6, AssetClass.BOND, 0.4));

        var dto = AllocationDto.fromAllocation(alloc);

        assertThat(dto.usStock()).isEqualByComparingTo("60");
        assertThat(dto.bond()).isEqualByComparingTo("40");
        assertThat(dto.intlStock()).isEqualByComparingTo("0");
    }
}
