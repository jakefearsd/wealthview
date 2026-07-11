package com.wealthview.core.projection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.AssetClassReturnEntity;
import com.wealthview.persistence.repository.AssetClassReturnRepository;

@Component
public class CapitalMarketAssumptionsProvider {

    private static final AssetClass[] CLASS_ORDER = AssetClass.values();

    private final AssetClassReturnRepository repository;
    private final AtomicReference<RealReturnMatrix> cachedMatrix = new AtomicReference<>();
    private final AtomicReference<Map<AssetClass, Double>> cachedGeoMeans = new AtomicReference<>();

    /**
     * A dense grid of historical real (inflation-adjusted) returns by year and asset class.
     *
     * <p><strong>Read-only view — do not mutate.</strong> Instances of this record are built once
     * and cached by {@link CapitalMarketAssumptionsProvider} for reuse across the Monte Carlo hot
     * path. The {@code years}, {@code classes}, and {@code realReturns} accessors return the
     * backing arrays <em>by reference</em>, not a defensive copy — cheap access is the whole point
     * of the cache. Any caller that mutates an element of these arrays corrupts the shared cache
     * for every other caller. Treat every array returned from this record as immutable; copy it
     * yourself before mutating if you ever need a scratch buffer.
     */
    public record RealReturnMatrix(int[] years, AssetClass[] classes, double[][] realReturns) {}

    public CapitalMarketAssumptionsProvider(AssetClassReturnRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the cached {@link RealReturnMatrix}, building it on first access.
     *
     * <p><strong>Read-only view — do not mutate.</strong> The returned matrix (and its
     * {@code years}/{@code classes}/{@code realReturns} arrays) is the single shared instance
     * cached for all callers; it is not copied per call. Callers MUST NOT write into any of its
     * arrays. This is a deliberate performance tradeoff for the Monte Carlo hot path — do not
     * "fix" this by adding defensive copies here, as that would defeat the cache.
     */
    public RealReturnMatrix matrix() {
        var existing = cachedMatrix.get();
        if (existing != null) {
            return existing;
        }
        var built = buildMatrix();
        cachedMatrix.set(built);
        return built;
    }

    public Map<AssetClass, Double> geometricMeans() {
        var existing = cachedGeoMeans.get();
        if (existing != null) {
            return existing;
        }
        var built = geometricMeansOf(matrix());
        cachedGeoMeans.set(built);
        return built;
    }

    /**
     * Computes per-asset-class geometric-mean real returns from an arbitrary {@link RealReturnMatrix}
     * — the same algorithm {@link #geometricMeans()} caches for the provider's own matrix, exposed as
     * a pure function so other callers that already hold a matrix (e.g. the Roth-conversion optimizer's
     * guardrail-flow return resolution, audit C4) can derive the identical figure without re-deriving
     * the math or routing through the cache/repository.
     */
    public static Map<AssetClass, Double> geometricMeansOf(RealReturnMatrix m) {
        return buildGeoMeans(m);
    }

    void clearCache() {
        cachedMatrix.set(null);
        cachedGeoMeans.set(null);
    }

    private RealReturnMatrix buildMatrix() {
        var rows = repository.findAllByOrderByYearAscAssetClassAsc();
        if (rows.isEmpty()) {
            throw new IllegalStateException("asset_class_returns is empty; seed data missing");
        }
        var byYear = new TreeMap<Integer, Map<AssetClass, Double>>();
        for (AssetClassReturnEntity r : rows) {
            byYear.computeIfAbsent(r.getYear(), k -> new EnumMap<>(AssetClass.class))
                    .put(AssetClass.fromKey(r.getAssetClass()), r.getRealReturn().doubleValue());
        }
        var years = new ArrayList<Integer>();
        var grid = new ArrayList<double[]>();
        for (var e : byYear.entrySet()) {
            if (e.getValue().size() != CLASS_ORDER.length) {
                throw new IllegalStateException("Year " + e.getKey() + " missing an asset class");
            }
            double[] row = new double[CLASS_ORDER.length];
            for (int i = 0; i < CLASS_ORDER.length; i++) {
                row[i] = e.getValue().get(CLASS_ORDER[i]);
            }
            years.add(e.getKey());
            grid.add(row);
        }
        return new RealReturnMatrix(years.stream().mapToInt(Integer::intValue).toArray(),
                CLASS_ORDER.clone(), grid.toArray(new double[0][]));
    }

    private static Map<AssetClass, Double> buildGeoMeans(RealReturnMatrix m) {
        var means = new EnumMap<AssetClass, Double>(AssetClass.class);
        for (int c = 0; c < m.classes().length; c++) {
            double product = 1.0;
            for (double[] yearRow : m.realReturns()) {
                product *= (1.0 + yearRow[c]);
            }
            means.put(m.classes()[c], Math.pow(product, 1.0 / m.realReturns().length) - 1.0);
        }
        return Map.copyOf(means);
    }
}
