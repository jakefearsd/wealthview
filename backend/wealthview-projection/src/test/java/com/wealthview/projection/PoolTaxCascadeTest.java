package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The double-precision tax-payment cascade shared by {@link TrialSimulator} and
 * {@code ConversionSimulator}: a tax bill is drawn from taxable, then traditional, then Roth.
 *
 * <p>It had no direct test — it was only ever exercised incidentally through trial simulation,
 * which never drove a bill deep enough to reach the Roth arm, so the "tax owed exceeds taxable
 * plus traditional" path was dead in tests. That is precisely the case that matters: it is what
 * happens late in a depleted retirement, and getting it wrong either drives a pool negative
 * (inventing money) or leaves the bill partly unpaid (understating depletion).
 */
class PoolTaxCascadeTest {

    private static final double TOL = 1e-9;

    private static void assertPools(double[] actual, double taxable, double traditional, double roth) {
        assertThat(actual[0]).as("taxable").isCloseTo(taxable, within(TOL));
        assertThat(actual[1]).as("traditional").isCloseTo(traditional, within(TOL));
        assertThat(actual[2]).as("roth").isCloseTo(roth, within(TOL));
    }

    @Test
    void deduct_amountFitsInTaxable_leavesTheOtherPoolsUntouched() {
        assertPools(PoolTaxCascade.deduct(300, 1_000, 2_000, 3_000), 700, 2_000, 3_000);
    }

    @Test
    void deduct_amountExceedsTaxable_spillsIntoTraditionalOnly() {
        assertPools(PoolTaxCascade.deduct(1_500, 1_000, 2_000, 3_000), 0, 1_500, 3_000);
    }

    @Test
    void deduct_amountExceedsTaxableAndTraditional_reachesRoth() {
        // The previously-dead arm: 4,000 owed against 1,000 taxable + 2,000 traditional leaves
        // 1,000 to come out of Roth.
        assertPools(PoolTaxCascade.deduct(4_000, 1_000, 2_000, 3_000), 0, 0, 2_000);
    }

    @Test
    void deduct_amountExceedsEveryPool_drainsToZeroWithoutGoingNegative() {
        // A shortfall must not invent money by driving a pool below zero — the pools simply empty.
        assertPools(PoolTaxCascade.deduct(99_999, 1_000, 2_000, 3_000), 0, 0, 0);
    }

    @Test
    void deduct_amountExactlyEqualsTheCombinedBalance_emptiesEveryPool() {
        assertPools(PoolTaxCascade.deduct(6_000, 1_000, 2_000, 3_000), 0, 0, 0);
    }

    // === pools that are absent or already empty ===

    @Test
    void deduct_emptyTaxable_startsAtTraditional() {
        assertPools(PoolTaxCascade.deduct(500, 0, 2_000, 3_000), 0, 1_500, 3_000);
    }

    @Test
    void deduct_onlyRothFunded_drawsEntirelyFromRoth() {
        assertPools(PoolTaxCascade.deduct(500, 0, 0, 3_000), 0, 0, 2_500);
    }

    @Test
    void deduct_negativePoolBalances_areSkippedRatherThanDrawnFurtherNegative() {
        // A pool can arrive slightly negative from floating-point drift upstream; the cascade must
        // step over it rather than deepen it.
        var result = PoolTaxCascade.deduct(500, -10, 2_000, 3_000);

        assertThat(result[0]).as("an already-negative pool is left exactly as-is").isCloseTo(-10, within(TOL));
        assertThat(result[1]).isCloseTo(1_500, within(TOL));
        assertThat(result[2]).isCloseTo(3_000, within(TOL));
    }

    // === degenerate amounts ===

    @Test
    void deduct_zeroAmount_returnsThePoolsUnchanged() {
        assertPools(PoolTaxCascade.deduct(0, 1_000, 2_000, 3_000), 1_000, 2_000, 3_000);
    }

    @Test
    void deduct_negativeAmount_isTreatedAsNothingOwedRatherThanACredit() {
        // A negative bill must not be added back to the pools as a refund.
        assertPools(PoolTaxCascade.deduct(-500, 1_000, 2_000, 3_000), 1_000, 2_000, 3_000);
    }

    @Test
    void deduct_conservesTotalDollarsWheneverTheBillIsAffordable() {
        double taxable = 1_234.56;
        double traditional = 2_345.67;
        double roth = 3_456.78;
        double bill = 5_000;

        var result = PoolTaxCascade.deduct(bill, taxable, traditional, roth);

        assertThat(result[0] + result[1] + result[2])
                .as("every dollar of an affordable bill leaves the pools exactly once")
                .isCloseTo(taxable + traditional + roth - bill, within(1e-6));
    }
}
