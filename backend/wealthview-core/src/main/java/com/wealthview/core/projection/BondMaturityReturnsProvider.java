package com.wealthview.core.projection;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.persistence.repository.BondMaturityReturnRepository;

/**
 * Serves the tent's per-maturity real returns, aligned to a {@link CapitalMarketAssumptionsProvider.RealReturnMatrix}.
 *
 * <p>Nothing consumes this provider until the bond tent's projection wiring (plan 3). The stored series covers
 * 1954-2025 for {@link BondMaturity#SHORT}, {@link BondMaturity#INTERMEDIATE} and {@link BondMaturity#LONG};
 * ultrashort is always the base matrix's cash column.
 */
@Component
public class BondMaturityReturnsProvider {

    private static final List<BondMaturity> STORED_MATURITIES =
            List.of(BondMaturity.SHORT, BondMaturity.INTERMEDIATE, BondMaturity.LONG);

    private final BondMaturityReturnRepository repository;
    private final AtomicReference<NavigableMap<Integer, Map<BondMaturity, Double>>> cachedSeries =
            new AtomicReference<>();

    /**
     * A dense grid of real returns by year and bond maturity, aligned to a base return matrix.
     *
     * <p><strong>Freshly built per call.</strong> {@link BondMaturityReturnsProvider#alignedTo} returns new arrays
     * each time, so a caller may mutate what it receives without affecting later callers. The columns follow
     * {@link BondMaturity#values()}, shortest-first.
     */
    public record MaturityReturnMatrix(int[] years, BondMaturity[] maturities, double[][] realReturns) {}

    public BondMaturityReturnsProvider(BondMaturityReturnRepository repository) {
        this.repository = repository;
    }

    /**
     * Aligns the stored series to the base matrix's years. Row {@code i} is {@code base.years()[i]}; the
     * ultrashort column is the base's CASH value for that row.
     *
     * @throws IllegalStateException if a base year has no stored row, or the base has no CASH column
     */
    public MaturityReturnMatrix alignedTo(CapitalMarketAssumptionsProvider.RealReturnMatrix base) {
        int cashColumn = Arrays.asList(base.classes()).indexOf(AssetClass.CASH);
        if (cashColumn < 0) {
            throw new IllegalStateException("Base return matrix has no CASH column");
        }
        var series = series();
        var maturities = BondMaturity.values();
        int[] years = base.years().clone();
        var grid = new double[years.length][maturities.length];
        for (int i = 0; i < years.length; i++) {
            var stored = series.get(years[i]);
            if (stored == null) {
                throw new IllegalStateException("No bond maturity returns for " + years[i]
                        + "; the series cover " + firstYear() + "-" + lastYear());
            }
            for (int c = 0; c < maturities.length; c++) {
                grid[i][c] = maturities[c] == BondMaturity.ULTRASHORT
                        ? base.realReturns()[i][cashColumn]
                        : stored.get(maturities[c]);
            }
        }
        return new MaturityReturnMatrix(years, maturities, grid);
    }

    /** First year of the stored series. */
    public int firstYear() {
        return series().firstKey();
    }

    /** Last year of the stored series. */
    public int lastYear() {
        return series().lastKey();
    }

    /**
     * Per-maturity compound mean, {@code (product of (1 + r))^(1/n) - 1}, over every row of {@code m}.
     */
    public static Map<BondMaturity, Double> geometricMeansOf(MaturityReturnMatrix m) {
        var means = new EnumMap<BondMaturity, Double>(BondMaturity.class);
        for (int c = 0; c < m.maturities().length; c++) {
            final int column = c;
            double growth = Arrays.stream(m.realReturns())
                    .mapToDouble(row -> 1.0 + row[column])
                    .reduce(1.0, (a, b) -> a * b);
            means.put(m.maturities()[c], Math.pow(growth, 1.0 / m.realReturns().length) - 1.0);
        }
        return Map.copyOf(means);
    }

    void clearCache() {
        cachedSeries.set(null);
    }

    private NavigableMap<Integer, Map<BondMaturity, Double>> series() {
        var existing = cachedSeries.get();
        if (existing != null) {
            return existing;
        }
        var built = loadSeries();
        cachedSeries.set(built);
        return built;
    }

    private NavigableMap<Integer, Map<BondMaturity, Double>> loadSeries() {
        var rows = repository.findAllByOrderByYearAscMaturityAsc();
        if (rows.isEmpty()) {
            throw new IllegalStateException("bond_maturity_returns is empty; seed data missing");
        }
        var byYear = new TreeMap<Integer, Map<BondMaturity, Double>>();
        for (var r : rows) {
            BondMaturity maturity = BondMaturity.fromKey(r.getMaturity());
            byYear.computeIfAbsent(r.getYear(), k -> new EnumMap<>(BondMaturity.class))
                    .put(maturity, r.getRealReturn().doubleValue());
        }
        for (var entry : byYear.entrySet()) {
            for (BondMaturity required : STORED_MATURITIES) {
                if (!entry.getValue().containsKey(required)) {
                    throw new IllegalStateException("Year " + entry.getKey() + " missing a bond maturity");
                }
            }
        }
        return byYear;
    }
}
