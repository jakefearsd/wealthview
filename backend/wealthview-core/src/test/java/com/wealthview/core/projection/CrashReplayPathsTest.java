package com.wealthview.core.projection;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.HistoricalReturnPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrashReplayPathsTest {

    private static final int[] MATRIX_YEARS = {1950, 1951, 1952, 1953, 1954, 1955, 1956};
    private static final int[] SLICE_YEARS = {1954, 1955, 1956};

    private final CapitalMarketAssumptionsProvider capitalMarket = mock(CapitalMarketAssumptionsProvider.class);
    private final BondMaturityReturnsProvider maturityReturns = mock(BondMaturityReturnsProvider.class);
    private final BondTentAssumptions tentAssumptions = mock(BondTentAssumptions.class);
    private final CrashReplayPaths paths = new CrashReplayPaths(capitalMarket, maturityReturns, tentAssumptions);

    private static CapitalMarketAssumptionsProvider.RealReturnMatrix fullMatrix() {
        var classes = AssetClass.values();
        int cash = Arrays.asList(classes).indexOf(AssetClass.CASH);
        int stock = Arrays.asList(classes).indexOf(AssetClass.US_STOCK);
        var grid = new double[MATRIX_YEARS.length][classes.length];
        for (int i = 0; i < MATRIX_YEARS.length; i++) {
            grid[i][stock] = (MATRIX_YEARS[i] - 1950) / 100.0;
            grid[i][cash] = 0.001 * (MATRIX_YEARS[i] - 1950);
        }
        return new CapitalMarketAssumptionsProvider.RealReturnMatrix(MATRIX_YEARS.clone(), classes.clone(), grid);
    }

    private static Map<BondMaturity, Double> flatMaturities(double ultrashort) {
        return Map.of(BondMaturity.ULTRASHORT, ultrashort, BondMaturity.SHORT, 0.01,
                BondMaturity.INTERMEDIATE, 0.01, BondMaturity.LONG, 0.01);
    }

    private static BondMaturityReturnsProvider.MaturityReturnMatrix sliceMaturities() {
        var maturities = BondMaturity.values();
        var grid = new double[SLICE_YEARS.length][maturities.length];
        for (int i = 0; i < SLICE_YEARS.length; i++) {
            for (int c = 0; c < maturities.length; c++) {
                grid[i][c] = maturities[c] == BondMaturity.ULTRASHORT
                        ? 0.001 * (SLICE_YEARS[i] - 1950)
                        : 0.02 * (c + 1) + (SLICE_YEARS[i] - 1950) / 1000.0;
            }
        }
        return new BondMaturityReturnsProvider.MaturityReturnMatrix(SLICE_YEARS.clone(), maturities, grid);
    }

    private void stubHistory() {
        when(capitalMarket.matrix(true)).thenReturn(fullMatrix());
        when(maturityReturns.firstYear()).thenReturn(1954);
        when(maturityReturns.lastYear()).thenReturn(1956);
        when(maturityReturns.alignedTo(any())).thenAnswer(invocation -> {
            var base = (CapitalMarketAssumptionsProvider.RealReturnMatrix) invocation.getArgument(0);
            if (!Arrays.equals(base.years(), SLICE_YEARS)) {
                throw new IllegalStateException("No bond maturity returns for " + base.years()[0]);
            }
            return sliceMaturities();
        });
        when(capitalMarket.geometricMeans(false)).thenReturn(Map.of(AssetClass.US_STOCK, 0.05));
        when(tentAssumptions.realReturns()).thenReturn(flatMaturities(0.005));
    }

    @Test
    void firstAndLastYear_areTheOverlapOfBothSeries() {
        stubHistory();

        assertThat(paths.firstYear()).isEqualTo(1954);
        assertThat(paths.lastYear()).isEqualTo(1956);
    }

    @Test
    void build_mapsCalendarYearsOntoHistory() {
        stubHistory();

        HistoricalReturnPath path = paths.build(1955, 2030, 2029, 2031);

        assertThat(path.forYear(2029).historyYear()).isEqualTo(1954);
        assertThat(path.forYear(2029).assets().get(AssetClass.US_STOCK)).isEqualTo(0.04, within(1e-12));
        assertThat(path.forYear(2030).historyYear()).isEqualTo(1955);
        assertThat(path.forYear(2031).historyYear()).isEqualTo(1956);
    }

    @Test
    void build_yearsOutsideHistory_useLongRunAverages() {
        stubHistory();

        HistoricalReturnPath path = paths.build(1955, 2030, 2026, 2034);

        for (int y : new int[] {2026, 2027, 2028, 2032, 2033, 2034}) {
            assertThat(path.forYear(y).historyYear()).isNull();
            assertThat(path.forYear(y).assets().get(AssetClass.US_STOCK)).isEqualTo(0.05, within(1e-12));
            assertThat(path.forYear(y).maturities()).containsEntry(BondMaturity.LONG, 0.01);
        }
    }

    @Test
    void build_ultrashortIsTheYearsCashReturn() {
        stubHistory();

        HistoricalReturnPath path = paths.build(1955, 2030, 2030, 2030);

        assertThat(path.forYear(2030).maturities().get(BondMaturity.ULTRASHORT))
                .isEqualTo(0.005, within(1e-12));
    }

    @Test
    void build_neverAlignsTheUnslicedMatrix() {
        stubHistory();

        paths.build(1955, 2030, 2026, 2034);

        verify(maturityReturns, never()).alignedTo(argThat(m -> !Arrays.equals(m.years(), SLICE_YEARS)));
    }
}
