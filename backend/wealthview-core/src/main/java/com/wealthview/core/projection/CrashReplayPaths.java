package com.wealthview.core.projection;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.HistoricalReturnPath;
import com.wealthview.core.projection.dto.YearReturns;

/**
 * Maps a crash replay's calendar years onto the historical real returns.
 *
 * <p>History covers the years where both the asset class returns and the bond maturity returns exist, which is
 * 1954-2025 today. The asset class series is read with {@code matrix(true)} because history is history: the
 * scenario's depression-years flag does not apply to a replay. Every other calendar year carries the long-run
 * averages the deterministic engine already uses, so a replay outside history matches the main projection.
 */
@Component
public class CrashReplayPaths {

    private final CapitalMarketAssumptionsProvider capitalMarket;
    private final BondMaturityReturnsProvider maturityReturns;
    private final BondTentAssumptions tentAssumptions;
    private final AtomicReference<HistorySlice> cachedSlice = new AtomicReference<>();

    public CrashReplayPaths(CapitalMarketAssumptionsProvider capitalMarket,
                            BondMaturityReturnsProvider maturityReturns,
                            BondTentAssumptions tentAssumptions) {
        this.capitalMarket = capitalMarket;
        this.maturityReturns = maturityReturns;
        this.tentAssumptions = tentAssumptions;
    }

    /** First historical year with both asset class and bond maturity returns. */
    public int firstYear() {
        return slice().firstYear();
    }

    /** Last historical year with both asset class and bond maturity returns. */
    public int lastYear() {
        return slice().lastYear();
    }

    /**
     * Builds the returns for each calendar year in {@code [fromCalendarYear, toCalendarYear]}. Calendar year {@code Y}
     * replays history year {@code crashFirstYear + (Y - crashCalendarYear)}. Years whose history year is out of range
     * are left to {@link HistoricalReturnPath#forYear(int)}, which answers with the long-run averages.
     */
    public HistoricalReturnPath build(int crashFirstYear, int crashCalendarYear, int fromCalendarYear,
                                      int toCalendarYear) {
        var slice = slice();
        var historyYears = slice.assets().years();
        var byCalendarYear = new HashMap<Integer, YearReturns>();
        for (int calendarYear = fromCalendarYear; calendarYear <= toCalendarYear; calendarYear++) {
            int historyYear = crashFirstYear + calendarYear - crashCalendarYear;
            int row = Arrays.binarySearch(historyYears, historyYear);
            if (row >= 0) {
                byCalendarYear.put(calendarYear, historicalYear(slice, row, historyYear));
            }
        }
        var longRun = new YearReturns(null, capitalMarket.geometricMeans(false), tentAssumptions.realReturns());
        return new HistoricalReturnPath(byCalendarYear, longRun);
    }

    private static YearReturns historicalYear(HistorySlice slice, int row, int historyYear) {
        var assets = new EnumMap<AssetClass, Double>(AssetClass.class);
        var classes = slice.assets().classes();
        for (int c = 0; c < classes.length; c++) {
            assets.put(classes[c], slice.assets().realReturns()[row][c]);
        }
        var maturities = new EnumMap<BondMaturity, Double>(BondMaturity.class);
        var columns = slice.maturities().maturities();
        for (int c = 0; c < columns.length; c++) {
            maturities.put(columns[c], slice.maturities().realReturns()[row][c]);
        }
        return new YearReturns(historyYear, assets, maturities);
    }

    private HistorySlice slice() {
        var existing = cachedSlice.get();
        if (existing != null) {
            return existing;
        }
        var built = buildSlice();
        cachedSlice.set(built);
        return built;
    }

    /**
     * Slices the asset matrix to the years the bond maturity series covers before aligning. Aligning the full matrix
     * would fail, because the maturity series has no rows before its first year.
     */
    private HistorySlice buildSlice() {
        var full = capitalMarket.matrix(true);
        int low = Math.max(full.years()[0], maturityReturns.firstYear());
        int high = Math.min(full.years()[full.years().length - 1], maturityReturns.lastYear());
        if (low > high) {
            throw new IllegalStateException("No year has both asset class and bond maturity returns");
        }
        var sliced = sliceTo(full, low, high);
        return new HistorySlice(sliced, maturityReturns.alignedTo(sliced));
    }

    /** A fresh matrix over the years in {@code [low, high]}; the source arrays are copied, never shared. */
    private static CapitalMarketAssumptionsProvider.RealReturnMatrix sliceTo(
            CapitalMarketAssumptionsProvider.RealReturnMatrix full, int low, int high) {
        int[] years = IntStream.of(full.years()).filter(y -> y >= low && y <= high).toArray();
        var rows = new double[years.length][];
        int next = 0;
        for (int i = 0; i < full.years().length; i++) {
            if (full.years()[i] >= low && full.years()[i] <= high) {
                rows[next++] = full.realReturns()[i].clone();
            }
        }
        return new CapitalMarketAssumptionsProvider.RealReturnMatrix(years, full.classes().clone(), rows);
    }

    /** The cached history window: asset returns and the maturity returns aligned to the same years. */
    private record HistorySlice(CapitalMarketAssumptionsProvider.RealReturnMatrix assets,
                                BondMaturityReturnsProvider.MaturityReturnMatrix maturities) {

        int firstYear() {
            return assets.years()[0];
        }

        int lastYear() {
            return assets.years()[assets.years().length - 1];
        }
    }
}
