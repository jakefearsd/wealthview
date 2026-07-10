package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetClassTest {

    @Test
    void fromKey_validKey_returnsEnum() {
        assertThat(AssetClass.fromKey("intl_stock")).isEqualTo(AssetClass.INTL_STOCK);
    }

    @Test
    void key_forEachConstant_roundTrips() {
        for (AssetClass ac : AssetClass.values()) {
            assertThat(AssetClass.fromKey(ac.key())).isEqualTo(ac);
        }
    }

    @Test
    void fromKey_unknownKey_throws() {
        assertThatThrownBy(() -> AssetClass.fromKey("crypto"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
