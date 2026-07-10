package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.AssetClassReturnEntity;
import com.wealthview.persistence.repository.AssetClassReturnRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapitalMarketAssumptionsProviderTest {

    private static AssetClassReturnEntity row(int y, String c, String r) {
        return new AssetClassReturnEntity(y, c, new BigDecimal(r));
    }

    @Test
    void matrix_completeData_buildsAlignedGrid() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01"),
                row(1973, "us_stock", "-0.05"), row(1973, "intl_stock", "-0.03"),
                row(1973, "bond", "0.03"), row(1973, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        var m = provider.matrix();

        assertThat(m.years()).containsExactly(1972, 1973);
        int us = java.util.Arrays.asList(m.classes()).indexOf(AssetClass.US_STOCK);
        assertThat(m.realReturns()[0][us]).isEqualTo(0.10, within(1e-9));
    }

    @Test
    void geometricMeans_computesPerClassCompoundMean() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01"),
                row(1973, "us_stock", "0.10"), row(1973, "intl_stock", "0.08"),
                row(1973, "bond", "0.02"), row(1973, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThat(provider.geometricMeans().get(AssetClass.US_STOCK)).isEqualTo(0.10, within(1e-9));
    }

    @Test
    void matrix_yearMissingAClass_throws() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "bond", "0.02")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThatThrownBy(provider::matrix).isInstanceOf(IllegalStateException.class);
    }
}
