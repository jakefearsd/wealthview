package com.wealthview.projection;

import java.util.Map;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PortfolioReturnResolverTest {

    private static final AssetClass[] ORDER = AssetClass.values();

    @Test
    void resolveReal_blendsAllocationAgainstSampledYear() {
        // classes order = US, INTL, BOND, CASH
        double[][] grid = {
                {0.10, 0.05, 0.02, 0.01},   // year index 0
                {-0.20, -0.10, 0.04, 0.01}, // year index 1
        };
        var matrix = new RealReturnMatrix(new int[]{1972, 1973}, ORDER, grid);
        var alloc = AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 0.5, AssetClass.BOND, 0.5));

        double[] r = PortfolioReturnResolver.resolveReal(new int[]{0, 1}, alloc, matrix);

        assertThat(r[0]).isEqualTo(0.06, within(1e-9));   // .5*.10 + .5*.02
        assertThat(r[1]).isEqualTo(-0.08, within(1e-9));  // .5*-.20 + .5*.04
    }

    @Test
    void fixed_returnsConstantSeries() {
        assertThat(PortfolioReturnResolver.fixed(3, 0.04)).containsExactly(0.04, 0.04, 0.04);
    }
}
