package com.wealthview.core.holding;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyMarketDetectorTest {

    @Test
    void isMoneyMarket_spaxx_returnsTrue() {
        assertThat(MoneyMarketDetector.isMoneyMarket("SPAXX")).isTrue();
    }

    @Test
    void isMoneyMarket_amzn_returnsFalse() {
        assertThat(MoneyMarketDetector.isMoneyMarket("AMZN")).isFalse();
    }

    @Test
    void isMoneyMarket_null_returnsFalse() {
        assertThat(MoneyMarketDetector.isMoneyMarket(null)).isFalse();
    }

    @Test
    void isMoneyMarket_caseInsensitive_returnsTrue() {
        assertThat(MoneyMarketDetector.isMoneyMarket("spaxx")).isTrue();
        assertThat(MoneyMarketDetector.isMoneyMarket("Vmfxx")).isTrue();
    }

    @Test
    void defaultRate_returns4Percent() {
        assertThat(MoneyMarketDetector.defaultRate()).isEqualByComparingTo(new BigDecimal("4.0000"));
    }

    @Test
    void isMoneyMarket_allKnownSymbols_returnTrue() {
        assertThat(MoneyMarketDetector.isMoneyMarket("FDRXX")).isTrue();
        assertThat(MoneyMarketDetector.isMoneyMarket("FZFXX")).isTrue();
        assertThat(MoneyMarketDetector.isMoneyMarket("VMFXX")).isTrue();
        assertThat(MoneyMarketDetector.isMoneyMarket("VMMXX")).isTrue();
        assertThat(MoneyMarketDetector.isMoneyMarket("SWVXX")).isTrue();
        assertThat(MoneyMarketDetector.isMoneyMarket("SNVXX")).isTrue();
        assertThat(MoneyMarketDetector.isMoneyMarket("SPRXX")).isTrue();
    }
}
