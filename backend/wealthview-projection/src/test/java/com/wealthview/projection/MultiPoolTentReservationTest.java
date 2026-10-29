package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.by;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tent reservations that {@link MultiPoolTentTest} does not reach: a reservation held in the Roth pool, one held
 * across both owners' traditional slices, per-owner RMDs after sleeves were spent, and the tent's no-setup and
 * negative-cap edges.
 */
class MultiPoolTentReservationTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final List<PoolType> ROTH_FIRST = List.of(PoolType.ROTH, PoolType.TAXABLE, PoolType.TRADITIONAL);
    private static final List<PoolType> TRADITIONAL_FIRST =
            List.of(PoolType.TRADITIONAL, PoolType.TAXABLE, PoolType.ROTH);

    private static PoolStrategy.TentSetup setup(List<PoolType> order, Map<BondMaturity, BigDecimal> mix,
                                                 Map<BondMaturity, BigDecimal> real) {
        return new PoolStrategy.TentSetup(order, mix, real, by("0", "0", "0", "0"));
    }

    private static ProjectionAccountInput account(String balance, String type, String owner) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US, Optional.empty(),
                bd(balance), type, owner);
    }

    private static PoolStrategy.MultiPool pool(Map<PoolType, List<ProjectionAccountInput>> accounts, String rate) {
        BigDecimal r = bd(rate);
        return new PoolStrategy.MultiPool(accounts, r, r, r, r,
                PoolFixtures.singleFilerConfig(WithdrawalOrder.TAXABLE_FIRST));
    }

    private static PoolStrategy.MultiPool rothPool(String roth, String rate) {
        return pool(Map.of(PoolType.ROTH, List.of(account(roth, "roth", "primary"))), rate);
    }

    /** Traditional held by two owners (60k primary, 40k spouse) with an 80k tent: 25% ultrashort, 75% short. */
    private static PoolStrategy.MultiPool coupleTent() {
        var pool = pool(Map.of(PoolType.TRADITIONAL, List.of(
                account("60000", "traditional", "primary"), account("40000", "traditional", "spouse"))), "0");
        pool.configureTent(setup(TRADITIONAL_FIRST, by("0.25", "0.75", "0", "0"), by("0", "0", "0", "0")));
        pool.topUpTent(bd("80000"));
        return pool;
    }

    @Test
    void applyGrowth_rothHeldReservation_growsSleevesAtTheirOwnReturnAndTheRestAtThePoolRate() {
        var pool = rothPool("100000", "0.05");
        pool.configureTent(setup(ROTH_FIRST, by("1", "0", "0", "0"), by("0.01", "0.01", "0.01", "0.01")));
        pool.topUpTent(bd("80000"));

        pool.applyGrowth(false);

        // 20,000 free at 5% plus 80,000 of sleeve at 1%
        assertThat(pool.tentPools().snapshot().totalIn(PoolType.ROTH)).isEqualByComparingTo("80800");
        assertThat(pool.getTotal()).isEqualByComparingTo("101800");
    }

    @Test
    void executeWithdrawals_rothHeldReservation_spendsTheShortestSleeveOfTheRothPool() {
        var pool = rothPool("100000", "0");
        pool.configureTent(setup(ROTH_FIRST, by("0.25", "0.75", "0", "0"), by("0", "0", "0", "0")));
        pool.topUpTent(bd("80000"));

        pool.executeWithdrawals(bd("50000"), 2030, ZERO, ZERO, ZERO, 65);

        assertThat(pool.getTotal()).isEqualByComparingTo("50000");
        assertThat(pool.tentTotal()).isEqualByComparingTo("50000");
        assertThat(pool.tentByMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("0");
        assertThat(pool.tentByMaturity().get(BondMaturity.SHORT)).isEqualByComparingTo("50000");
    }

    @Test
    void topUpTent_reservationSpansBothOwnersTraditionalSlices_isOneHouseholdReservation() {
        var pool = coupleTent();

        assertThat(pool.tentTotal()).isEqualByComparingTo("80000");
        assertThat(pool.getTraditionalByOwner().get(PersonId.PRIMARY)).isEqualByComparingTo("60000");
        assertThat(pool.getTraditionalByOwner().get(PersonId.SPOUSE)).isEqualByComparingTo("40000");
        assertThat(pool.tentPools().snapshot().totalIn(PoolType.TRADITIONAL)).isEqualByComparingTo("80000");
    }

    @Test
    void forceRmd_spouseOwner_tentMigratesIntoTaxableWithoutLosingAnyOfIt() {
        var pool = coupleTent();

        pool.forceRmd(PersonId.SPOUSE, bd("40000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("80000");
        var reserved = pool.tentPools().snapshot();
        assertThat(reserved.totalIn(PoolType.TRADITIONAL)).isEqualByComparingTo("60000");
        assertThat(reserved.totalIn(PoolType.TAXABLE)).isEqualByComparingTo("20000");
        assertThat(pool.getTraditionalByOwner().get(PersonId.SPOUSE)).isEqualByComparingTo("0");
    }

    @Test
    void forceRmd_perOwnerAfterSleevesWereSpent_doesNotRestoreThem() {
        var pool = coupleTent();
        pool.executeWithdrawals(bd("50000"), 2030, ZERO, ZERO, ZERO, 65);

        pool.forceRmd(PersonId.SPOUSE, bd("20000"));

        // the withdrawal spent 30,000 of sleeves (all 20,000 ultrashort, then 10,000 short); the RMD only moves
        // what is left into taxable, so nothing is refilled
        assertThat(pool.tentTotal()).isEqualByComparingTo("50000");
        assertThat(pool.tentByMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("0");
        assertThat(pool.tentByMaturity().get(BondMaturity.SHORT)).isEqualByComparingTo("50000");
        assertThat(pool.tentPools().snapshot().totalIn(PoolType.TAXABLE)).isEqualByComparingTo("20000");
    }

    @Test
    void useSleeveReturns_withoutATentSetup_isANoOp() {
        var pool = rothPool("100000", "0.05");

        pool.tentPools().useSleeveReturns(by("0.01", "0.01", "0.01", "0.01"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("0");
        assertThat(pool.tentByMaturity()).isEmpty();
        pool.applyGrowth(false);
        assertThat(pool.getTotal()).isEqualByComparingTo("105000");
    }

    @Test
    void topUpTent_negativeCap_reservesNothing() {
        var pool = rothPool("100000", "0");
        pool.configureTent(setup(ROTH_FIRST, by("1", "0", "0", "0"), by("0", "0", "0", "0")));

        pool.topUpTent(bd("50000"), bd("-1000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("0");
    }
}
