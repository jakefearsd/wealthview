package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FilingStatus;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household task 4: owner-aware traditional/Roth pools in {@link PoolStrategy.MultiPool}. Verifies
 * contributions route by owner, within-type draws split proportionally (documented remainder
 * rounding), per-owner RMD forcing, sum accessors, and memento round-trip of the owner-keyed state.
 */
class MultiPoolOwnerTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static ProjectionAccountInput trad(String balance, String contribution, String owner) {
        return new HypotheticalAccountInput(bd(balance), bd(contribution), AssetAllocation.ALL_US,
                Optional.empty(), bd(balance), "traditional", owner);
    }

    private static ProjectionAccountInput owned(String balance, String costBasis, String type, String owner) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US,
                Optional.empty(), bd(costBasis), type, owner);
    }

    /** A MultiPool with the given traditional accounts, zero growth and no tax calculator. */
    private static PoolStrategy.MultiPool tradPool(List<ProjectionAccountInput> traditionalAccounts) {
        var config = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        return new PoolStrategy.MultiPool(
                Map.of(PoolStrategy.POOL_TRADITIONAL, traditionalAccounts), ZERO, config);
    }

    /** A MultiPool over the given type-grouped accounts, MFJ, zero growth, no tax calculator. */
    private static PoolStrategy.MultiPool pool(Map<String, List<ProjectionAccountInput>> grouped) {
        var config = new PoolStrategy.PoolConfig(
                FilingStatus.MARRIED_FILING_JOINTLY, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        return new PoolStrategy.MultiPool(grouped, ZERO, config);
    }

    // === Household task 5: first-death transition on the pools ===

    @Test
    void applyFirstDeathTransition_rollsDeceasedTraditionalIntoSurvivor_conservingTotal() {
        var pool = pool(Map.of(
                PoolStrategy.POOL_TRADITIONAL, List.of(
                        owned("300000", "0", "traditional", "primary"),
                        owned("500000", "0", "traditional", "spouse")),
                PoolStrategy.POOL_ROTH, List.of(
                        owned("100000", "0", "roth", "primary"),
                        owned("200000", "0", "roth", "spouse"))));
        BigDecimal totalBefore = pool.getTotal();

        // Primary dies; spouse survives.
        pool.applyFirstDeathTransition(PersonId.PRIMARY, PersonId.SPOUSE, false);

        // Rollover conservation pin: total unchanged.
        assertThat(pool.getTotal()).isEqualByComparingTo(totalBefore);
        // Traditional: deceased (primary) zeroed, survivor (spouse) holds the sum.
        var trad = pool.getTraditionalByOwner();
        assertThat(trad.get(PersonId.PRIMARY)).isEqualByComparingTo(ZERO);
        assertThat(trad.get(PersonId.SPOUSE)).isEqualByComparingTo(bd("800000"));
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("800000"));
    }

    @Test
    void applyFirstDeathTransition_flipsFilingStatusToSingle() {
        var pool = pool(Map.of(PoolStrategy.POOL_TRADITIONAL,
                List.of(owned("300000", "0", "traditional", "primary"))));
        assertThat(pool.getFilingStatus()).isEqualTo(FilingStatus.MARRIED_FILING_JOINTLY);

        pool.applyFirstDeathTransition(PersonId.SPOUSE, PersonId.PRIMARY, false);

        assertThat(pool.getFilingStatus()).isEqualTo(FilingStatus.SINGLE);
    }

    @Test
    void applyFirstDeathTransition_survivorIsSpouse_rollsPrimaryPoolsIntoSpouse() {
        var pool = pool(Map.of(PoolStrategy.POOL_TRADITIONAL, List.of(
                owned("400000", "0", "traditional", "primary"),
                owned("100000", "0", "traditional", "spouse"))));

        pool.applyFirstDeathTransition(PersonId.PRIMARY, PersonId.SPOUSE, false);

        var trad = pool.getTraditionalByOwner();
        assertThat(trad.get(PersonId.PRIMARY)).isEqualByComparingTo(ZERO);
        assertThat(trad.get(PersonId.SPOUSE)).isEqualByComparingTo(bd("500000"));
    }

    @Test
    void applyFirstDeathTransition_thenSnapshotRestore_preservesTransitionExactlyOnce() {
        // Mirrors the Social Security convergence interaction: the transition fires, THEN the pool is
        // snapshotted, mutated by a convergence pass, and restored. The restore must return to the
        // POST-transition state (rollover + filing flip intact) and never re-apply or revert it.
        var pool = pool(Map.of(PoolStrategy.POOL_TRADITIONAL, List.of(
                owned("300000", "0", "traditional", "primary"),
                owned("500000", "0", "traditional", "spouse"))));

        pool.applyFirstDeathTransition(PersonId.PRIMARY, PersonId.SPOUSE, false); // rollover + filing flip
        var snapshot = pool.snapshot();          // B2 snapshot, taken AFTER the transition
        pool.forceRmd(PersonId.SPOUSE, bd("50000")); // a convergence-pass mutation
        pool.restore(snapshot);                   // B2 restore

        var trad = pool.getTraditionalByOwner();
        assertThat(trad.get(PersonId.PRIMARY)).isEqualByComparingTo(ZERO); // rollover still applied, once
        assertThat(trad.get(PersonId.SPOUSE)).isEqualByComparingTo(bd("800000"));
        assertThat(pool.getFilingStatus()).isEqualTo(FilingStatus.SINGLE); // filing flip survives restore
    }

    @Test
    void applyFirstDeathTransition_stepUpConservesTotalValue_jointTaxable() {
        // Joint taxable with embedded gain: basis 300k, value 500k.
        var pool = pool(Map.of(PoolStrategy.POOL_TAXABLE,
                List.of(owned("500000", "300000", "taxable", "joint"))));
        BigDecimal totalBefore = pool.getTotal();

        pool.applyFirstDeathTransition(PersonId.PRIMARY, PersonId.SPOUSE, false);

        // Step-up touches basis only, never value: total portfolio value is unchanged.
        assertThat(pool.getTotal()).isEqualByComparingTo(totalBefore);
    }

    @Test
    void applyContributions_ownerKeyedAccounts_growsEachOwnersPoolIndependently() {
        var pool = tradPool(List.of(
                trad("100000", "5000", "primary"),
                trad("200000", "8000", "spouse")));

        pool.applyContributions();

        var byOwner = pool.getTraditionalByOwner();
        assertThat(byOwner.get(PersonId.PRIMARY)).isEqualByComparingTo(bd("105000"));
        assertThat(byOwner.get(PersonId.SPOUSE)).isEqualByComparingTo(bd("208000"));
    }

    @Test
    void applyContributions_spouseOwnedContributionOnly_leavesPrimaryPoolUntouched() {
        var pool = tradPool(List.of(
                trad("100000", "0", "primary"),
                trad("200000", "9000", "spouse")));

        pool.applyContributions();

        var byOwner = pool.getTraditionalByOwner();
        assertThat(byOwner.get(PersonId.PRIMARY)).isEqualByComparingTo(bd("100000")); // untouched
        assertThat(byOwner.get(PersonId.SPOUSE)).isEqualByComparingTo(bd("209000"));
    }

    @Test
    void getTraditional_ownerKeyedPools_returnsSumAcrossOwners() {
        var pool = tradPool(List.of(
                trad("100000", "0", "primary"),
                trad("200000", "0", "spouse")));

        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("300000"));
    }

    @Test
    void forceRmd_perOwner_forcesFromThatOwnersPoolOnly() {
        var pool = tradPool(List.of(
                trad("300000", "0", "primary"),
                trad("500000", "0", "spouse")));

        var forced = pool.forceRmd(PersonId.PRIMARY, bd("10000"));

        assertThat(forced).isEqualByComparingTo(bd("10000"));
        var byOwner = pool.getTraditionalByOwner();
        assertThat(byOwner.get(PersonId.PRIMARY)).isEqualByComparingTo(bd("290000"));
        assertThat(byOwner.get(PersonId.SPOUSE)).isEqualByComparingTo(bd("500000")); // untouched
    }

    @Test
    void forceRmd_perOwner_capsAtThatOwnersBalance() {
        var pool = tradPool(List.of(
                trad("300000", "0", "primary"),
                trad("500000", "0", "spouse")));

        var forced = pool.forceRmd(PersonId.SPOUSE, bd("999999999"));

        assertThat(forced).isEqualByComparingTo(bd("500000"));
        assertThat(pool.getTraditionalByOwner().get(PersonId.SPOUSE)).isEqualByComparingTo(ZERO);
    }

    @Test
    void forceRmd_wholePoolProportionalSplit_debitsByOwnerBalanceWithRemainderConvention() {
        var pool = tradPool(List.of(
                trad("100000", "0", "primary"),
                trad("200000", "0", "spouse")));

        // Single-arg forceRmd splits proportionally: primary share = 50000 * 100000/300000 =
        // 16666.6667 (divide, scale 4, HALF_UP); spouse takes the exact remainder 33333.3333, so
        // the pool total drops by EXACTLY 50000.
        var forced = pool.forceRmd(bd("50000"));

        assertThat(forced).isEqualByComparingTo(bd("50000"));
        var byOwner = pool.getTraditionalByOwner();
        assertThat(byOwner.get(PersonId.PRIMARY)).isEqualByComparingTo(bd("83333.3333"));
        assertThat(byOwner.get(PersonId.SPOUSE)).isEqualByComparingTo(bd("166666.6667"));
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("250000"));
    }

    @Test
    void snapshotRestore_ownerKeyedState_roundTripsBothOwners() {
        var pool = tradPool(List.of(
                trad("100000", "0", "primary"),
                trad("200000", "0", "spouse")));
        var snapshot = pool.snapshot();

        pool.forceRmd(PersonId.PRIMARY, bd("40000"));
        pool.forceRmd(PersonId.SPOUSE, bd("60000"));
        pool.restore(snapshot);

        var byOwner = pool.getTraditionalByOwner();
        assertThat(byOwner.get(PersonId.PRIMARY)).isEqualByComparingTo(bd("100000"));
        assertThat(byOwner.get(PersonId.SPOUSE)).isEqualByComparingTo(bd("200000"));
    }

    @Test
    void getTraditionalByOwner_returnsDetachedCopy_mutationDoesNotAffectPool() {
        var pool = tradPool(List.of(trad("100000", "0", "primary")));

        pool.getTraditionalByOwner().put(PersonId.PRIMARY, bd("999"));

        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("100000"));
    }
}
