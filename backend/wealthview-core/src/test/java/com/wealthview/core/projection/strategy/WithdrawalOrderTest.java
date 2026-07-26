package com.wealthview.core.projection.strategy;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.PoolType;

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

    @Test
    void drawSequence_taxableFirst_drawsTaxableThenTraditionalThenRoth() {
        assertThat(WithdrawalOrder.TAXABLE_FIRST.drawSequence())
                .containsExactly(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);
    }

    @Test
    void drawSequence_traditionalFirst_drawsTraditionalThenTaxableThenRoth() {
        assertThat(WithdrawalOrder.TRADITIONAL_FIRST.drawSequence())
                .containsExactly(PoolType.TRADITIONAL, PoolType.TAXABLE, PoolType.ROTH);
    }

    @Test
    void drawSequence_rothFirst_drawsRothThenTaxableThenTraditional() {
        assertThat(WithdrawalOrder.ROTH_FIRST.drawSequence())
                .containsExactly(PoolType.ROTH, PoolType.TAXABLE, PoolType.TRADITIONAL);
    }

    @Test
    void drawSequence_proRata_fallsBackToTaxableFirstSequence() {
        // Pro-rata has no strict priority sequence: consumers that support proportional allocation
        // dispatch it before asking for a sequence, and the Monte Carlo path draws it taxable-first.
        assertThat(WithdrawalOrder.PRO_RATA.drawSequence())
                .containsExactly(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);
    }

    @Test
    void drawSequence_dynamicSequencing_fallsBackToTaxableFirstSequence() {
        // Dynamic sequencing is bracket-driven and dispatched ahead of the ordered strategies; the
        // sequence is only reached as the documented "no bracket rate configured" fallback.
        assertThat(WithdrawalOrder.DYNAMIC_SEQUENCING.drawSequence())
                .containsExactly(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);
    }

    @Test
    void drawSequence_everyOrder_permutesTheThreePoolsExactlyOnce() {
        for (WithdrawalOrder order : WithdrawalOrder.values()) {
            assertThat(order.drawSequence())
                    .as("draw sequence for %s", order)
                    .containsExactlyInAnyOrder(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);
        }
    }
}
