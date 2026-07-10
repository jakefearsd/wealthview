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

    public record RealReturnMatrix(int[] years, AssetClass[] classes, double[][] realReturns) {}

    public CapitalMarketAssumptionsProvider(AssetClassReturnRepository repository) {
        this.repository = repository;
    }

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
        var built = buildGeoMeans(matrix());
        cachedGeoMeans.set(built);
        return built;
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
