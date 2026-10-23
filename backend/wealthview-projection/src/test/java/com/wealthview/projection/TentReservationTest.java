package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

class TentReservationTest {

    private static Map<BondMaturity, BigDecimal> shares(String us, String sh, String in, String lg) {
        var map = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        map.put(BondMaturity.ULTRASHORT, bd(us));
        map.put(BondMaturity.SHORT, bd(sh));
        map.put(BondMaturity.INTERMEDIATE, bd(in));
        map.put(BondMaturity.LONG, bd(lg));
        return map;
    }

    private static TentReservation reserved100k() {
        var tent = TentReservation.empty();
        tent.reserve(PoolType.TAXABLE, bd("100000"), shares("0.2", "0.3", "0.5", "0"));
        return tent;
    }

    @Test
    void reserve_splitsAmountByMixShares() {
        var tent = reserved100k();

        assertThat(tent.byMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("20000");
        assertThat(tent.byMaturity().get(BondMaturity.SHORT)).isEqualByComparingTo("30000");
        assertThat(tent.byMaturity().get(BondMaturity.INTERMEDIATE)).isEqualByComparingTo("50000");
        assertThat(tent.byMaturity().get(BondMaturity.LONG)).isEqualByComparingTo("0");
        assertThat(tent.total()).isEqualByComparingTo("100000");
    }

    @Test
    void reserve_sharesThatDoNotDivideEvenly_sumsToExactAmount() {
        var tent = TentReservation.empty();

        tent.reserve(PoolType.ROTH, bd("100"), shares("0.3333", "0.3333", "0.3334", "0"));

        assertThat(tent.totalIn(PoolType.ROTH)).isEqualByComparingTo("100");
        assertThat(tent.isEmpty()).isFalse();
    }

    @Test
    void grow_eachSleeveAtItsOwnReturn() {
        var tent = reserved100k();

        var growth = tent.grow(PoolType.TAXABLE, shares("0.01", "0.02", "0.03", "0.04"));

        // 20,000 x 1% + 30,000 x 2% + 50,000 x 3% + 0 x 4%
        assertThat(growth).isEqualByComparingTo("2300");
        assertThat(tent.total()).isEqualByComparingTo("102300");
    }

    @Test
    void clampTo_balanceBelowReservation_removesShortestFirst() {
        var tent = reserved100k();

        tent.clampTo(PoolType.TAXABLE, bd("75000"));

        assertThat(tent.byMaturity().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("0");
        assertThat(tent.byMaturity().get(BondMaturity.SHORT)).isEqualByComparingTo("25000");
        assertThat(tent.byMaturity().get(BondMaturity.INTERMEDIATE)).isEqualByComparingTo("50000");
    }

    @Test
    void clampTo_balanceAboveReservation_changesNothing() {
        var tent = reserved100k();

        tent.clampTo(PoolType.TAXABLE, bd("500000"));

        assertThat(tent.total()).isEqualByComparingTo("100000");
    }

    @Test
    void interestIn_taxablePool_isSleeveTimesYield() {
        var tent = reserved100k();

        var interest = tent.interestIn(PoolType.TAXABLE, shares("0.04", "0.05", "0.06", "0.07"));

        // 20,000 x 4% + 30,000 x 5% + 50,000 x 6%
        assertThat(interest).isEqualByComparingTo("5300");
        assertThat(tent.total()).isEqualByComparingTo("100000");
        assertThat(tent.interestIn(PoolType.TRADITIONAL, shares("0.04", "0.05", "0.06", "0.07")))
                .isEqualByComparingTo("0");
    }

    @Test
    void copy_isIndependent() {
        var tent = reserved100k();
        var copy = tent.copy();

        tent.clampTo(PoolType.TAXABLE, bd("10"));

        assertThat(copy.total()).isEqualByComparingTo("100000");
    }
}
