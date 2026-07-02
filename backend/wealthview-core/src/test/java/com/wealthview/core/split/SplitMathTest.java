package com.wealthview.core.split;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SplitMathTest {

    @Test
    void adjustShares_forwardSplit_multipliesQuantity() {
        assertThat(SplitMath.adjustShares(new BigDecimal("100"), 4, 1))
                .isEqualByComparingTo("400.0000");
    }

    @Test
    void adjustShares_reverseSplit_keepsFractionalShares() {
        assertThat(SplitMath.adjustShares(new BigDecimal("155"), 1, 10))
                .isEqualByComparingTo("15.5000");
    }

    @Test
    void adjustShares_oddRatio_isExactNoDrift() {
        // 300 shares through a 1:3 reverse split is exactly 100 — the old
        // pre-divided scale-8 ratio drifted here.
        assertThat(SplitMath.adjustShares(new BigDecimal("300"), 1, 3))
                .isEqualByComparingTo("100.0000");
    }

    @Test
    void adjustPrice_forwardSplit_dividesPrice() {
        assertThat(SplitMath.adjustPrice(new BigDecimal("400"), 4, 1))
                .isEqualByComparingTo("100.0000");
    }

    @Test
    void adjustPrice_reverseSplit_multipliesPrice() {
        assertThat(SplitMath.adjustPrice(new BigDecimal("5"), 1, 10))
                .isEqualByComparingTo("50.0000");
    }
}
