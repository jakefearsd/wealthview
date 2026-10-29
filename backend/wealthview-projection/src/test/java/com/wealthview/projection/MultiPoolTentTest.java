package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.by;
import static org.assertj.core.api.Assertions.assertThat;

class MultiPoolTentTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final List<PoolType> TAXABLE_FIRST =
            List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);

    private static PoolStrategy.TentSetup setup(List<PoolType> order, Map<BondMaturity, BigDecimal> mix,
                                                 Map<BondMaturity, BigDecimal> real,
                                                 Map<BondMaturity, BigDecimal> nominal) {
        return new PoolStrategy.TentSetup(order, mix, real, nominal);
    }

    private static PoolStrategy.TentSetup simpleSetup() {
        return setup(TAXABLE_FIRST, by("0.5", "0.5", "0", "0"), by("0.01", "0.01", "0.01", "0.01"),
                by("0.04", "0.04", "0.04", "0.04"));
    }

    private static MultiPool pool(String taxable, String trad, String roth, String rate) {
        Map<PoolType, List<ProjectionAccountInput>> accounts = Map.of(
                PoolType.TAXABLE, List.of(new HypotheticalAccountInput(bd(taxable), ZERO, ZERO, "taxable")),
                PoolType.TRADITIONAL, List.of(new HypotheticalAccountInput(bd(trad), ZERO, ZERO, "traditional")),
                PoolType.ROTH, List.of(new HypotheticalAccountInput(bd(roth), ZERO, ZERO, "roth")));
        BigDecimal r = bd(rate);
        return new MultiPool(accounts, r, r, r, r,
                PoolFixtures.singleFilerConfig(WithdrawalOrder.TAXABLE_FIRST));
    }

    @Test
    void noTentConfigured_growthAndTotalsUnchanged() {
        var plain = pool("300000", "500000", "100000", "0.05");
        var twin = pool("300000", "500000", "100000", "0.05");
        twin.topUpTent(bd("200000"));

        var a = plain.applyGrowth(true);
        var b = twin.applyGrowth(true);

        assertThat(twin.tentTotal()).isEqualByComparingTo(ZERO);
        assertThat(twin.tentByMaturity()).isEmpty();
        assertThat(b).isEqualTo(a);
        assertThat(twin.getTotal()).isEqualByComparingTo(plain.getTotal());
    }

    @Test
    void topUpTent_reservesInPlacementOrder() {
        var pool = pool("300000", "500000", "0", "0");
        pool.configureTent(simpleSetup());

        pool.topUpTent(bd("200000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("200000");
        assertThat(pool.tentByMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("100000");
        assertThat(pool.getTraditional()).isEqualByComparingTo("500000");
    }

    @Test
    void topUpTent_firstPoolTooSmall_spillsToNextPool() {
        var pool = pool("50000", "500000", "0", "0");
        pool.configureTent(simpleSetup());

        pool.topUpTent(bd("200000"));
        // a second call with the same target adds nothing
        pool.topUpTent(bd("200000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("200000");
        assertThat(pool.tentByMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("100000");
    }

    @Test
    void topUpTent_targetAbovePortfolio_reservesEverything() {
        var pool = pool("20000", "50000", "50000", "0");
        pool.configureTent(simpleSetup());

        pool.topUpTent(bd("200000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("120000");
        assertThat(pool.getTotal()).isEqualByComparingTo("120000");
        assertThat(pool.getTraditional().signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void applyGrowth_reservedPool_growsSleevesAtMaturityReturns() {
        var pool = pool("0", "500000", "0", "0.05");
        pool.configureTent(setup(List.of(PoolType.TRADITIONAL, PoolType.TAXABLE, PoolType.ROTH),
                by("1", "0", "0", "0"), by("0.01", "0", "0", "0"), by("0", "0", "0", "0")));
        pool.topUpTent(bd("100000"));

        var growth = pool.applyGrowth(false);

        // 400,000 x 1.05 + 100,000 x 1.01
        assertThat(pool.getTraditional()).isEqualByComparingTo("521000");
        assertThat(growth.traditional()).isEqualByComparingTo("21000");
        assertThat(pool.tentTotal()).isEqualByComparingTo("101000");
    }

    @Test
    void applyGrowth_retiredTaxableReservation_booksOrdinaryInterest() {
        var pool = pool("100000", "0", "0", "0.05");
        pool.configureTent(simpleSetup());
        pool.topUpTent(bd("40000"));

        var growth = pool.applyGrowth(true);
        var result = pool.executeWithdrawals(ZERO, 2030, ZERO, ZERO, ZERO, 65);

        // 60,000 x 1.05 + 40,000 x 1.01
        assertThat(pool.getTotal()).isEqualByComparingTo("103400");
        assertThat(growth.taxable()).isEqualByComparingTo("3400");
        // 20,000 x 4% + 20,000 x 4%
        assertThat(result.ordinaryInterestIncome()).isEqualByComparingTo("1600");
    }

    @Test
    void applyGrowth_accumulationYearTaxableReservation_booksNoInterest() {
        var pool = pool("100000", "0", "0", "0.05");
        pool.configureTent(simpleSetup());
        pool.topUpTent(bd("40000"));

        pool.applyGrowth(false);
        var result = pool.executeWithdrawals(ZERO, 2030, ZERO, ZERO, ZERO, 45);

        assertThat(pool.getTotal()).isEqualByComparingTo("103400");
        assertThat(pool.taxableLotCount()).isEqualTo(1);
        assertThat(result.ordinaryInterestIncome()).isEqualByComparingTo(ZERO);
    }

    @Test
    void reconcile_debitBeyondGrowthPart_spendsShortestSleeveFirst() {
        var pool = pool("100000", "0", "0", "0");
        pool.configureTent(setup(TAXABLE_FIRST, by("0.25", "0.75", "0", "0"), by("0", "0", "0", "0"),
                by("0", "0", "0", "0")));
        pool.topUpTent(bd("80000"));

        pool.executeWithdrawals(bd("50000"), 2030, ZERO, ZERO, ZERO, 65);

        assertThat(pool.getTotal()).isEqualByComparingTo("50000");
        assertThat(pool.tentTotal()).isEqualByComparingTo("50000");
        assertThat(pool.tentByMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("0");
        assertThat(pool.tentByMaturity().get(BondMaturity.SHORT)).isEqualByComparingTo("50000");
    }

    @Test
    void clamp_debitBeyondPoolGrowthPart_movesExcessToNextPoolWithFreeBalance() {
        var pool = pool("100000", "500000", "0", "0");
        pool.configureTent(setup(TAXABLE_FIRST, by("0.25", "0.75", "0", "0"), by("0", "0", "0", "0"),
                by("0", "0", "0", "0")));
        pool.topUpTent(bd("80000"));

        pool.executeWithdrawals(bd("50000"), 2030, ZERO, ZERO, ZERO, 65);

        assertThat(pool.tentTotal()).isEqualByComparingTo("80000");   // clamps (lazily) before the snapshot
        var reserved = pool.tentPools().snapshot();
        assertThat(reserved.totalIn(PoolType.TAXABLE)).isEqualByComparingTo("50000");
        assertThat(reserved.totalIn(PoolType.TRADITIONAL)).isEqualByComparingTo("30000");
        assertThat(pool.tentByMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("20000");
        assertThat(pool.tentByMaturity().get(BondMaturity.SHORT)).isEqualByComparingTo("60000");
    }

    @Test
    void clamp_noFreeBalanceAnywhere_spendsShortestFirst() {
        var pool = pool("100000", "0", "0", "0");
        pool.configureTent(setup(TAXABLE_FIRST, by("0.25", "0.75", "0", "0"), by("0", "0", "0", "0"),
                by("0", "0", "0", "0")));
        pool.topUpTent(bd("80000"));

        pool.executeWithdrawals(bd("50000"), 2030, ZERO, ZERO, ZERO, 65);

        assertThat(pool.tentTotal()).isEqualByComparingTo("50000");
        assertThat(pool.tentByMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("0");
        assertThat(pool.tentByMaturity().get(BondMaturity.SHORT)).isEqualByComparingTo("50000");
    }

    private static MultiPool debitedIntoSleeves(String taxableContribution, String rate) {
        Map<PoolType, List<ProjectionAccountInput>> accounts = Map.of(
                PoolType.TAXABLE, List.of(new HypotheticalAccountInput(
                        bd("100000"), bd(taxableContribution), ZERO, "taxable")),
                PoolType.TRADITIONAL, List.of(new HypotheticalAccountInput(ZERO, ZERO, ZERO, "traditional")),
                PoolType.ROTH, List.of(new HypotheticalAccountInput(ZERO, ZERO, ZERO, "roth")));
        BigDecimal r = bd(rate);
        var pool = new MultiPool(accounts, r, r, r, r,
                PoolFixtures.singleFilerConfig(WithdrawalOrder.TAXABLE_FIRST));
        pool.configureTent(setup(TAXABLE_FIRST, by("0.25", "0.75", "0", "0"), by("0.01", "0.01", "0.01", "0.01"),
                by("0", "0", "0", "0")));
        pool.topUpTent(bd("80000"));
        pool.executeWithdrawals(bd("50000"), 2030, ZERO, ZERO, ZERO, 65);
        return pool;
    }

    @Test
    void depositToTaxable_afterDebitIntoSleeves_doesNotRestoreSpentSleeves() {
        var pool = debitedIntoSleeves("0", "0");

        pool.depositToTaxable(bd("30000"));

        assertThat(pool.getTotal()).isEqualByComparingTo("80000");
        assertThat(pool.tentTotal()).isEqualByComparingTo("50000");
        assertThat(pool.tentByMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("0");
    }

    @Test
    void applyContributions_afterDebitIntoSleeves_doesNotRestoreSpentSleeves() {
        var pool = debitedIntoSleeves("30000", "0");

        pool.applyContributions();

        assertThat(pool.getTotal()).isEqualByComparingTo("80000");
        assertThat(pool.tentTotal()).isEqualByComparingTo("50000");
    }

    @Test
    void applyGrowth_afterDebitIntoSleeves_growsOnlyRemainingSleeves() {
        var pool = debitedIntoSleeves("0", "0.05");

        pool.applyGrowth(false);

        // 50,000 left, all of it sleeve, growing at 1% (not the 5% growth-asset rate)
        assertThat(pool.getTotal()).isEqualByComparingTo("50500");
        assertThat(pool.tentTotal()).isEqualByComparingTo("50500");
    }

    @Test
    void snapshotRestore_restoresReservation() {
        var pool = pool("100000", "0", "0", "0.05");
        pool.configureTent(simpleSetup());
        pool.topUpTent(bd("40000"));
        var memento = pool.snapshot();
        pool.applyGrowth(true);
        var grownTent = pool.tentTotal();

        pool.restore(memento);

        assertThat(grownTent).isEqualByComparingTo("40400");
        assertThat(pool.tentTotal()).isEqualByComparingTo("40000");
        assertThat(pool.getTotal()).isEqualByComparingTo("100000");
        pool.restore(memento);
        pool.applyGrowth(true);
        assertThat(pool.tentTotal()).isEqualByComparingTo("40400");
    }

    @Test
    void placementFor_traditionalFirst_putsTraditionalFirst() {
        assertThat(PoolStrategy.TentSetup.placementFor(WithdrawalOrder.TRADITIONAL_FIRST))
                .containsExactly(PoolType.TRADITIONAL, PoolType.TAXABLE, PoolType.ROTH);
    }

    private static final List<PoolType> TRADITIONAL_FIRST =
            List.of(PoolType.TRADITIONAL, PoolType.TAXABLE, PoolType.ROTH);

    /** Traditional 100k holding an 80k tent (25% ultrashort, 75% short); taxable and Roth empty. */
    private static MultiPool tentInTraditional() {
        var pool = pool("0", "100000", "0", "0");
        pool.configureTent(setup(TRADITIONAL_FIRST, by("0.25", "0.75", "0", "0"), by("0", "0", "0", "0"),
                by("0", "0", "0", "0")));
        pool.topUpTent(bd("80000"));
        return pool;
    }

    @Test
    void executeRothConversion_onlyFreeRoomIsTheCreditedDollars_tentMigratesIntoRoth() {
        var pool = tentInTraditional();

        pool.executeRothConversionOverride(2030, ZERO, bd("50000"), ZERO, ZERO);

        assertThat(pool.tentTotal()).isEqualByComparingTo("80000");
        var reserved = pool.tentPools().snapshot();
        assertThat(reserved.totalIn(PoolType.TRADITIONAL)).isEqualByComparingTo("50000");
        assertThat(reserved.totalIn(PoolType.ROTH)).isEqualByComparingTo("30000");
        assertThat(pool.getTraditional()).isEqualByComparingTo("50000");
    }

    @Test
    void executeRothConversion_afterEarlierDebitIntoSleeves_doesNotRestoreSpentSleeves() {
        var pool = tentInTraditional();
        pool.executeWithdrawals(bd("50000"), 2030, ZERO, ZERO, ZERO, 65);

        pool.executeRothConversionOverride(2030, ZERO, bd("30000"), ZERO, ZERO);

        // the 50k withdrawal spent 30k of sleeves (no free room then); the conversion moves 30k of what is left
        assertThat(pool.tentTotal()).isEqualByComparingTo("50000");
        assertThat(pool.tentPools().snapshot().totalIn(PoolType.ROTH)).isEqualByComparingTo("30000");
    }

    @Test
    void forceRmd_onlyFreeRoomIsTheCreditedDollars_tentMigratesIntoTaxable() {
        var pool = tentInTraditional();

        pool.forceRmd(bd("50000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("80000");
        var reserved = pool.tentPools().snapshot();
        assertThat(reserved.totalIn(PoolType.TRADITIONAL)).isEqualByComparingTo("50000");
        assertThat(reserved.totalIn(PoolType.TAXABLE)).isEqualByComparingTo("30000");
        assertThat(pool.getTraditional()).isEqualByComparingTo("50000");
    }

    @Test
    void forceRmd_perOwner_onlyFreeRoomIsTheCreditedDollars_tentMigratesIntoTaxable() {
        var pool = tentInTraditional();

        pool.forceRmd(PersonId.PRIMARY, bd("50000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("80000");
        assertThat(pool.tentPools().snapshot().totalIn(PoolType.TAXABLE)).isEqualByComparingTo("30000");
    }

    @Test
    void forceRmd_afterEarlierDebitIntoSleeves_doesNotRestoreSpentSleeves() {
        var pool = tentInTraditional();
        pool.executeWithdrawals(bd("50000"), 2030, ZERO, ZERO, ZERO, 65);

        pool.forceRmd(bd("30000"));

        assertThat(pool.tentTotal()).isEqualByComparingTo("50000");
    }
}
