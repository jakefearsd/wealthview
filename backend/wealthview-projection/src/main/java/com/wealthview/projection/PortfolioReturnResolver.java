package com.wealthview.projection;

import java.util.Arrays;
import java.util.EnumMap;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;

final class PortfolioReturnResolver {

    private PortfolioReturnResolver() {
    }

    static double[] resolveReal(int[] indexSequence, AssetAllocation allocation, RealReturnMatrix matrix) {
        var classIdx = new EnumMap<AssetClass, Integer>(AssetClass.class);
        for (int i = 0; i < matrix.classes().length; i++) {
            classIdx.put(matrix.classes()[i], i);
        }
        double[] weightByClassIdx = new double[matrix.classes().length];
        allocation.weights().forEach((cls, w) -> {
            Integer i = classIdx.get(cls);
            if (i != null) {
                weightByClassIdx[i] = w.doubleValue();
            }
        });
        double[] out = new double[indexSequence.length];
        for (int y = 0; y < indexSequence.length; y++) {
            double[] yearRow = matrix.realReturns()[indexSequence[y]];
            double r = 0.0;
            for (int c = 0; c < yearRow.length; c++) {
                r += weightByClassIdx[c] * yearRow[c];
            }
            out[y] = r;
        }
        return out;
    }

    static double[] fixed(int years, double realReturn) {
        double[] out = new double[years];
        Arrays.fill(out, realReturn);
        return out;
    }
}
