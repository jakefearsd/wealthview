package com.wealthview.projection;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors {@link TaxableLotsTest} in exact BigDecimal arithmetic — the deterministic engine's
 * golden-file reproducible lot structure.
 */
class TaxableLotsBdTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void sellFifo_partialOldestLot_realizesProportionalGain() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100"));            // lot A: basis 100, value 100
        lots.grow(bd("1.0"));              // A now value 200 (gain 100); basis 100
        lots.addLot(bd("50"));             // lot B: basis 50, value 50 (no gain)
        // total value 250. Sell 100 -> all from oldest lot A (value 200).
        // gain = 100 * (200-100)/200 = 50.
        BigDecimal gain = lots.sellFifo(bd("100"));

        assertThat(gain).isEqualByComparingTo(bd("50"));
        assertThat(lots.totalValue()).isEqualByComparingTo(bd("150")); // 100 left in A + 50 in B
        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("100")); // A basis 50 remaining + B basis 50
    }

    @Test
    void sellFifo_spanningLots_drawsOldestFirst() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100"));
        lots.grow(bd("1.0"));   // A: basis 100, value 200 (gain 100)
        lots.addLot(bd("100")); // B: basis 100, value 100 (no gain)

        // sell 300 (all): gain = 100 (from A) + 0 (from B) = 100
        assertThat(lots.sellFifo(bd("300"))).isEqualByComparingTo(bd("100"));
        assertThat(lots.totalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void grow_appreciatesValueNotBasis() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("1000"));

        lots.grow(bd("0.10"));

        assertThat(lots.totalValue()).isEqualByComparingTo(bd("1100"));
        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("1000"));
    }

    @Test
    void seedLot_carriesEmbeddedGain() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("60000"), bd("100000")); // basis 60k, value 100k → 40k embedded gain

        assertThat(lots.totalValue()).isEqualByComparingTo(bd("100000"));
        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("60000"));
        // selling everything realizes the full embedded gain
        assertThat(lots.sellFifo(bd("100000"))).isEqualByComparingTo(bd("40000"));
    }

    @Test
    void sellFifo_amountAboveTotal_cappedAtTotalValue() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100"));
        lots.grow(bd("0.50")); // value 150, basis 100

        // asking for more than exists sells only what's there, gain = 50
        assertThat(lots.sellFifo(bd("999"))).isEqualByComparingTo(bd("50"));
        assertThat(lots.totalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
