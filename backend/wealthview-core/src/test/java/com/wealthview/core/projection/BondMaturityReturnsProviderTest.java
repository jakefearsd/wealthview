package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.persistence.entity.BondMaturityReturnEntity;
import com.wealthview.persistence.repository.BondMaturityReturnRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BondMaturityReturnsProviderTest {

    private static BondMaturityReturnEntity row(int y, String m, String r) {
        return new BondMaturityReturnEntity(y, m, new BigDecimal(r));
    }

    /** Base matrix over all AssetClass columns; CASH = 0.001 * (year - 1950), every other class 0.0. */
    private static CapitalMarketAssumptionsProvider.RealReturnMatrix base(int... years) {
        var classes = AssetClass.values();
        int cash = List.of(classes).indexOf(AssetClass.CASH);
        var grid = new double[years.length][classes.length];
        for (int i = 0; i < years.length; i++) {
            grid[i][cash] = 0.001 * (years[i] - 1950);
        }
        return new CapitalMarketAssumptionsProvider.RealReturnMatrix(years.clone(), classes.clone(), grid);
    }

    private static BondMaturityReturnsProvider providerWith(List<BondMaturityReturnEntity> rows) {
        var repo = mock(BondMaturityReturnRepository.class);
        when(repo.findAllByOrderByYearAscMaturityAsc()).thenReturn(rows);
        return new BondMaturityReturnsProvider(repo);
    }

    private static List<BondMaturityReturnEntity> completeRows1972And1973() {
        return List.of(
                row(1972, "short", "0.030"), row(1972, "intermediate", "0.040"), row(1972, "long", "0.050"),
                row(1973, "short", "-0.010"), row(1973, "intermediate", "0.020"), row(1973, "long", "-0.030"));
    }

    @Test
    void alignedTo_baseWithinSeries_rowsFollowBaseYearsAndUltrashortIsCash() {
        var provider = providerWith(completeRows1972And1973());

        var m = provider.alignedTo(base(1972, 1973));

        assertThat(m.years()).containsExactly(1972, 1973);
        assertThat(m.maturities()).containsExactly(BondMaturity.values());
        assertThat(m.realReturns()[0][BondMaturity.ULTRASHORT.ordinal()]).isEqualTo(0.022, within(1e-9));
        assertThat(m.realReturns()[1][BondMaturity.LONG.ordinal()]).isEqualTo(-0.030, within(1e-9));
    }

    @Test
    void alignedTo_baseYearBeforeSeries_throwsNamingTheYear() {
        var provider = providerWith(completeRows1972And1973());

        assertThatThrownBy(() -> provider.alignedTo(base(1971, 1972)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1971");
    }

    @Test
    void alignedTo_baseYearAfterSeries_throwsNamingTheYear() {
        var provider = providerWith(completeRows1972And1973());

        assertThatThrownBy(() -> provider.alignedTo(base(1973, 1974)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1974");
    }

    @Test
    void alignedTo_yearMissingAStoredMaturity_throws() {
        var provider = providerWith(List.of(
                row(1972, "short", "0.030"), row(1972, "intermediate", "0.040"), row(1972, "long", "0.050"),
                row(1973, "short", "-0.010"), row(1973, "intermediate", "0.020")));

        assertThatThrownBy(() -> provider.alignedTo(base(1972, 1973)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1973");
    }

    @Test
    void alignedTo_emptyTable_throws() {
        var provider = providerWith(List.of());

        assertThatThrownBy(() -> provider.alignedTo(base(1972)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bond_maturity_returns is empty");
    }

    @Test
    void alignedTo_unknownMaturityKey_throwsIllegalArgument() {
        var provider = providerWith(List.of(
                row(1972, "short", "0.030"), row(1972, "intermediate", "0.040"), row(1972, "long", "0.050"),
                row(1972, "ultra", "0.010")));

        assertThatThrownBy(() -> provider.alignedTo(base(1972)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alignedTo_baseWithoutCashColumn_throws() {
        var provider = providerWith(completeRows1972And1973());
        var noCash = new CapitalMarketAssumptionsProvider.RealReturnMatrix(
                new int[] {1972}, new AssetClass[] {AssetClass.US_STOCK}, new double[][] {{0.10}});

        assertThatThrownBy(() -> provider.alignedTo(noCash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CASH");
    }

    @Test
    void alignedTo_mutatingAReturnedMatrix_doesNotLeakIntoTheNextCall() {
        var provider = providerWith(completeRows1972And1973());
        var first = provider.alignedTo(base(1972, 1973));

        first.realReturns()[0][BondMaturity.SHORT.ordinal()] = 99.0;
        var second = provider.alignedTo(base(1972, 1973));

        assertThat(second.realReturns()[0][BondMaturity.SHORT.ordinal()]).isEqualTo(0.030, within(1e-9));
    }

    @Test
    void alignedTo_calledTwice_readsTheRepositoryOnce() {
        var repo = mock(BondMaturityReturnRepository.class);
        when(repo.findAllByOrderByYearAscMaturityAsc()).thenReturn(completeRows1972And1973());
        var provider = new BondMaturityReturnsProvider(repo);

        provider.alignedTo(base(1972, 1973));
        provider.alignedTo(base(1972, 1973));

        verify(repo, times(1)).findAllByOrderByYearAscMaturityAsc();
    }

    @Test
    void firstYearAndLastYear_reportStoredSeriesBounds() {
        var provider = providerWith(completeRows1972And1973());

        assertThat(provider.firstYear()).isEqualTo(1972);
        assertThat(provider.lastYear()).isEqualTo(1973);
    }

    @Test
    void geometricMeansOf_computesPerMaturityCompoundMean() {
        var matrix = new BondMaturityReturnsProvider.MaturityReturnMatrix(
                new int[] {2000, 2001}, BondMaturity.values(),
                new double[][] {{0.01, 0.10, 0.20, 0.30}, {0.02, -0.05, 0.40, -0.10}});

        Map<BondMaturity, Double> means = BondMaturityReturnsProvider.geometricMeansOf(matrix);

        assertThat(means.get(BondMaturity.SHORT)).isEqualTo(Math.sqrt(1.10 * 0.95) - 1, within(1e-12));
        assertThat(means.get(BondMaturity.ULTRASHORT)).isEqualTo(Math.sqrt(1.01 * 1.02) - 1, within(1e-12));
    }

    @Test
    void geometricMeansOf_iteratesShortestFirst() {
        var matrix = new BondMaturityReturnsProvider.MaturityReturnMatrix(
                new int[] {2000, 2001}, BondMaturity.values(),
                new double[][] {{0.01, 0.10, 0.20, 0.30}, {0.02, -0.05, 0.40, -0.10}});

        var means = BondMaturityReturnsProvider.geometricMeansOf(matrix);

        assertThat(means.keySet()).containsExactly(BondMaturity.values());
    }

    @Test
    void fromKey_unknownKey_throws() {
        assertThatThrownBy(() -> BondMaturity.fromKey("ultra"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void values_areDeclaredShortestFirst() {
        assertThat(BondMaturity.values())
                .containsExactly(BondMaturity.ULTRASHORT, BondMaturity.SHORT,
                        BondMaturity.INTERMEDIATE, BondMaturity.LONG);
    }
}
