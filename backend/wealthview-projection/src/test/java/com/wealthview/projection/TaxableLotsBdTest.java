package com.wealthview.projection;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.LotOwner;

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

    // === HP1: EXACT per-owner first-death basis step-up ===

    /** Joint + decedent-owned + survivor-owned lots with distinct embedded gains, each stepped by
     * its OWN statutory factor — not one blended rate applied uniformly. */
    @Test
    void stepUpByOwner_mixedOwners_stepsEachLotByItsOwnStatutoryFactor() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100"), bd("300"), LotOwner.JOINT);   // gain 200
        lots.addLot(bd("100"), bd("200"), LotOwner.SPOUSE);  // gain 100, decedent-owned
        lots.addLot(bd("100"), bd("500"), LotOwner.PRIMARY); // gain 400, survivor-owned
        BigDecimal valueBefore = lots.totalValue();

        // Spouse predeceases; common-law joint rate 0.5.
        lots.stepUpByOwner(LotOwner.SPOUSE, new BigDecimal("0.5"));

        // joint 100+200*0.5=200; spouse(decedent) 100+100*1.0=200; primary(survivor) 100+400*0=100
        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("500"));
        assertThat(lots.totalValue()).isEqualByComparingTo(valueBefore); // value untouched (conservation)
    }

    /** HP3 Part B-3 (HP1 review Minor): the BigDecimal mirror of
     * {@link TaxableLotsTest#stepUpByOwner_decedentOwnedLot_retaggedToSurvivor}'s economic
     * double-death proof, stronger than the snapshot-ordinal-only pin below. The spouse's lot
     * belongs to the survivor (primary) after the step-up. Probe: re-grow a fresh gain, then name
     * PRIMARY as a (hypothetical) later decedent -- the lot steps FULLY, which only happens if it
     * was actually retagged SPOUSE -> PRIMARY (else it would still be the survivor, factor 0.0, and
     * the fresh gain would stay untouched). */
    @Test
    void stepUpByOwner_decedentOwnedLot_retaggedToSurvivor_provenEconomically() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100"), bd("200"), LotOwner.SPOUSE); // gain 100

        lots.stepUpByOwner(LotOwner.SPOUSE, new BigDecimal("0.5")); // basis -> 200 (full), retag SPOUSE->PRIMARY
        lots.grow(new BigDecimal("0.5"));                           // value 300, basis 200: fresh 100 gain
        lots.stepUpByOwner(LotOwner.PRIMARY, new BigDecimal("0.5"));

        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("300")); // stepped fully -> proves retag
    }

    @Test
    void stepUpByOwner_decedentOwnedLot_retaggedToSurvivorAfterStep() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100"), bd("300"), LotOwner.JOINT);
        lots.addLot(bd("100"), bd("200"), LotOwner.SPOUSE);  // decedent-owned
        lots.addLot(bd("100"), bd("500"), LotOwner.PRIMARY); // survivor-owned

        lots.stepUpByOwner(LotOwner.SPOUSE, new BigDecimal("0.5"));

        var snap = lots.snapshot(); // owner code is the 3rd slot
        assertThat(snap.get(1)[2].intValue()).isEqualTo(LotOwner.PRIMARY.ordinal()); // spouse -> survivor
        assertThat(snap.get(0)[2].intValue()).isEqualTo(LotOwner.JOINT.ordinal());   // joint unchanged
        assertThat(snap.get(2)[2].intValue()).isEqualTo(LotOwner.PRIMARY.ordinal()); // survivor unchanged
    }

    /** Community property steps a joint lot fully (1.0) — the exact rate reaches each lot. */
    @Test
    void stepUpByOwner_communityPropertyJointFactor_stepsJointLotFully() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("100"), bd("300"), LotOwner.JOINT); // gain 200

        lots.stepUpByOwner(LotOwner.SPOUSE, BigDecimal.ONE); // communityProperty => jointFactor 1.0

        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("300")); // fully stepped to value
    }

    /** Reinvested household flows (dividends, interest, RMD after-tax remainder, surplus) enter via
     * the owner-less addLot overloads and are tagged JOINT — so at first death they step by the joint
     * rate, not by any account-owner rate. Pins that convention. */
    @Test
    void addLot_ownerlessOverloadTagsJoint_soReinvestedLotStepsAtJointRate() {
        var lots = new TaxableLotsBd();
        lots.addLot(bd("60"), bd("100")); // reinvested-style lot (40 embedded gain), defaults JOINT

        lots.stepUpByOwner(LotOwner.SPOUSE, new BigDecimal("0.5")); // spouse dies, common-law 0.5

        assertThat(lots.totalBasis()).isEqualByComparingTo(bd("80")); // 60 + 40*0.5 => joint rate applied
    }
}
