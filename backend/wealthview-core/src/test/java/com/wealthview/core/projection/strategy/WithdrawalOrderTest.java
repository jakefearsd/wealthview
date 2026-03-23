package com.wealthview.core.projection.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalOrderTest {

    @Test
    void fromString_taxableFirst_returnsTaxableFirst() {
        assertThat(WithdrawalOrder.fromString("taxable_first")).isEqualTo(WithdrawalOrder.TAXABLE_FIRST);
    }

    @Test
    void fromString_traditionalFirst_returnsTraditionalFirst() {
        assertThat(WithdrawalOrder.fromString("traditional_first")).isEqualTo(WithdrawalOrder.TRADITIONAL_FIRST);
    }

    @Test
    void fromString_rothFirst_returnsRothFirst() {
        assertThat(WithdrawalOrder.fromString("roth_first")).isEqualTo(WithdrawalOrder.ROTH_FIRST);
    }

    @Test
    void fromString_proRata_returnsProRata() {
        assertThat(WithdrawalOrder.fromString("pro_rata")).isEqualTo(WithdrawalOrder.PRO_RATA);
    }

    @Test
    void fromString_null_returnsTaxableFirst() {
        assertThat(WithdrawalOrder.fromString(null)).isEqualTo(WithdrawalOrder.TAXABLE_FIRST);
    }

    @Test
    void fromString_unknown_returnsTaxableFirst() {
        assertThat(WithdrawalOrder.fromString("garbage")).isEqualTo(WithdrawalOrder.TAXABLE_FIRST);
    }

    @Test
    void fromString_dynamicSequencing_returnsDynamicSequencing() {
        assertThat(WithdrawalOrder.fromString("dynamic_sequencing"))
                .isEqualTo(WithdrawalOrder.DYNAMIC_SEQUENCING);
    }
}
