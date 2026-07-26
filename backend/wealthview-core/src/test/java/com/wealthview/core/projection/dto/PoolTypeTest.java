package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PoolTypeTest {

    @Test
    void fromString_taxable_returnsTaxable() {
        assertThat(PoolType.fromString("taxable")).isEqualTo(PoolType.TAXABLE);
    }

    @Test
    void fromString_traditional_returnsTraditional() {
        assertThat(PoolType.fromString("traditional")).isEqualTo(PoolType.TRADITIONAL);
    }

    @Test
    void fromString_roth_returnsRoth() {
        assertThat(PoolType.fromString("roth")).isEqualTo(PoolType.ROTH);
    }

    @Test
    void fromString_upperCase_isCaseInsensitive() {
        assertThat(PoolType.fromString("TAXABLE")).isEqualTo(PoolType.TAXABLE);
    }

    @Test
    void fromString_null_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> PoolType.fromString(null));
    }

    @Test
    void fromString_unknown_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> PoolType.fromString("garbage"));
    }

    @Test
    void wireValue_roundTripsThroughFromString() {
        for (PoolType type : PoolType.values()) {
            assertThat(PoolType.fromString(type.wireValue())).isEqualTo(type);
        }
    }
}
