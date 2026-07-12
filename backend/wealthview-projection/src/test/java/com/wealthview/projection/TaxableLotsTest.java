package com.wealthview.projection;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TaxableLotsTest {

    @Test
    void sellFifo_partialOldestLot_realizesProportionalGain() {
        var lots = new TaxableLots();
        lots.addLot(100);            // lot A: basis 100, value 100
        lots.grow(1.0);              // A now value 200 (gain 100); basis 100
        lots.addLot(50);             // lot B: basis 50, value 50 (no gain)
        // total value 250. Sell 100 -> all from oldest lot A (value 200).
        // gain = 100 * (200-100)/200 = 50.
        double gain = lots.sellFifo(100);
        assertThat(gain).isEqualTo(50.0, within(1e-9));
        assertThat(lots.totalValue()).isEqualTo(150.0, within(1e-9)); // 100 left in A + 50 in B
        assertThat(lots.totalBasis()).isEqualTo(100.0, within(1e-9)); // A basis 50 remaining + B basis 50
    }

    @Test
    void sellFifo_spanningLots_drawsOldestFirst() {
        var lots = new TaxableLots();
        lots.addLot(100); lots.grow(1.0);   // A: basis 100, value 200 (gain 100)
        lots.addLot(100);                    // B: basis 100, value 100 (no gain)
        // sell 300 (all): gain = 100 (from A) + 0 (from B) = 100
        assertThat(lots.sellFifo(300)).isEqualTo(100.0, within(1e-9));
        assertThat(lots.totalValue()).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void addLot_withExplicitBasisAndValue_carriesEmbeddedGain() {
        var lots = new TaxableLots();
        lots.addLot(600, 1000);   // seed a lot worth 1000 with a 600 cost basis (400 embedded gain)

        assertThat(lots.totalValue()).isEqualTo(1000.0, within(1e-9));
        assertThat(lots.totalBasis()).isEqualTo(600.0, within(1e-9));
        // Selling the whole lot realizes exactly the embedded gain.
        assertThat(lots.sellFifo(1000)).isEqualTo(400.0, within(1e-9));
    }

    @Test
    void grow_appreciatesValueNotBasis() {
        var lots = new TaxableLots();
        lots.addLot(1000);
        lots.grow(0.10);
        assertThat(lots.totalValue()).isEqualTo(1100.0, within(1e-9));
        assertThat(lots.totalBasis()).isEqualTo(1000.0, within(1e-9));
    }

    @Test
    void stepUp_fullFactor_setsBasisToValueSoNoGainRemains() {
        // Household task 6: full step-up (community-property / deceased-owned) zeroes the embedded
        // gain -- a subsequent full sale realizes nothing.
        var lots = new TaxableLots();
        lots.addLot(600, 1000);   // 400 embedded gain

        lots.stepUp(1.0);

        assertThat(lots.totalValue()).isEqualTo(1000.0, within(1e-9)); // value untouched
        assertThat(lots.totalBasis()).isEqualTo(1000.0, within(1e-9)); // basis stepped to value
        assertThat(lots.sellFifo(1000)).isEqualTo(0.0, within(1e-9));  // no gain left
    }

    @Test
    void stepUp_halfFactor_stepsUpHalfTheEmbeddedGain() {
        // Common-law joint default: basis += (value - basis) * 0.5.
        var lots = new TaxableLots();
        lots.addLot(600, 1000);   // 400 embedded gain

        lots.stepUp(0.5);

        assertThat(lots.totalBasis()).isEqualTo(800.0, within(1e-9)); // 600 + 0.5*400
        assertThat(lots.totalValue()).isEqualTo(1000.0, within(1e-9));
        assertThat(lots.sellFifo(1000)).isEqualTo(200.0, within(1e-9)); // remaining gain
    }

    @Test
    void stepUp_perLot_appliesToEachLotIndependently() {
        var lots = new TaxableLots();
        lots.addLot(100, 300);   // 200 gain
        lots.addLot(500, 500);   // no gain

        lots.stepUp(1.0);

        assertThat(lots.totalBasis()).isEqualTo(800.0, within(1e-9)); // 300 + 500
        assertThat(lots.totalValue()).isEqualTo(800.0, within(1e-9));
    }

    @Test
    void stepUp_zeroFactor_isNoOp() {
        var lots = new TaxableLots();
        lots.addLot(600, 1000);
        lots.stepUp(0.0);
        assertThat(lots.totalBasis()).isEqualTo(600.0, within(1e-9));
    }

    @Test
    void consolidateIfNeeded_overCap_mergesOldestPreservingTotals() {
        var lots = new TaxableLots();
        for (int i = 0; i < 10; i++) { lots.addLot(100); lots.grow(0.0); }
        double vBefore = lots.totalValue(), bBefore = lots.totalBasis();
        lots.consolidateIfNeeded(3);
        assertThat(lots.totalValue()).isEqualTo(vBefore, within(1e-6));
        assertThat(lots.totalBasis()).isEqualTo(bBefore, within(1e-6));
    }
}
