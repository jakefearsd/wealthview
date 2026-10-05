package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalValueTest {

    @Test
    void compute_typicalPools_taxesOnlyTraditionalAtHeirRate() {
        // 100,000 x (1 - 0.24) = 76,000 ; + 50,000 Roth + 25,000 taxable (basis steps up) = 151,000
        var value = TerminalValue.compute(2060, new BigDecimal("100000"), new BigDecimal("50000"),
                new BigDecimal("25000"), new BigDecimal("0.24"), false);

        assertThat(value.afterTaxLegacy()).isEqualByComparingTo("151000.0000");
        assertThat(value.afterTaxLegacy().scale()).isEqualTo(4);
        assertThat(value.year()).isEqualTo(2060);
        assertThat(value.heirTaxRate()).isEqualByComparingTo("0.24");
        assertThat(value.atSecondDeath()).isFalse();
    }

    @Test
    void compute_zeroHeirRate_legacyEqualsPoolSum() {
        var value = TerminalValue.compute(2060, new BigDecimal("100000"), new BigDecimal("50000"),
                new BigDecimal("25000"), BigDecimal.ZERO, true);

        assertThat(value.afterTaxLegacy()).isEqualByComparingTo("175000");
        assertThat(value.atSecondDeath()).isTrue();
    }

    @Test
    void compute_fiftyPercentHeirRate_halvesTraditional() {
        // 100,000.01 x 0.5 = 50,000.005 -> rounds HALF_UP at scale 4 = 50,000.0050
        var value = TerminalValue.compute(2060, new BigDecimal("100000.01"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.50"), false);

        assertThat(value.afterTaxLegacy()).isEqualByComparingTo("50000.0050");
    }
}
