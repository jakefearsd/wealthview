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

    // === Household task 5: first-death basis step-up ===

    @Test
    void stepUp_fullFactor_setsBasisToValueAndEliminatesGain() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("60000"), bd("100000")); // basis 60k, value 100k → 40k embedded gain

        lots.stepUp(BigDecimal.ONE); // full step-up: basis := value

        assertThat(lots.totalValue()).isEqualByComparingTo(bd("100000")); // value untouched
        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("100000")); // basis stepped to value
        // a subsequent full sale now realizes ZERO gain
        assertThat(lots.sellFifo(bd("100000"))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void stepUp_halfFactor_movesBasisHalfwayToValue() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("60000"), bd("100000")); // 40k embedded gain

        lots.stepUp(new BigDecimal("0.5")); // basis += 40k * 0.5 = 20k → 80k

        assertThat(lots.totalValue()).isEqualByComparingTo(bd("100000")); // value untouched
        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("80000"));
        // remaining realizable gain is halved: 100k - 80k = 20k
        assertThat(lots.sellFifo(bd("100000"))).isEqualByComparingTo(bd("20000"));
    }

    @Test
    void stepUp_perLot_appliesFormulaToEachLotIndependently() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100"), bd("300"));  // gain 200
        lots.addLot(bd("500"), bd("500"));  // gain 0 (at cost)

        lots.stepUp(new BigDecimal("0.5"));

        // lot A basis 100 + 200*0.5 = 200; lot B basis 500 + 0 = 500 → total 700
        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("700"));
        assertThat(lots.totalValue()).isEqualByComparingTo(bd("800"));
    }

    @Test
    void stepUp_lossLot_stepsBasisDownToValueAtFullFactor() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100000"), bd("60000")); // basis above value: a 40k embedded loss

        lots.stepUp(BigDecimal.ONE); // step to FMV: basis := value

        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("60000"));
        assertThat(lots.totalValue()).isEqualByComparingTo(bd("60000"));
    }
}
