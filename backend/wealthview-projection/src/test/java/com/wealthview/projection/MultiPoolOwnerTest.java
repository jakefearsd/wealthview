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

    /** A MultiPool with the given traditional accounts, zero growth and no tax calculator. */
    private static PoolStrategy.MultiPool tradPool(List<ProjectionAccountInput> traditionalAccounts) {
        var config = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        return new PoolStrategy.MultiPool(
                Map.of(PoolStrategy.POOL_TRADITIONAL, traditionalAccounts), ZERO, config);
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
