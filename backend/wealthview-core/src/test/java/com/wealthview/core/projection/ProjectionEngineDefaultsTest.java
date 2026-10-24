package com.wealthview.core.projection;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.HistoricalReturnPath;
import com.wealthview.core.projection.dto.YearReturns;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionEngineDefaultsTest {

    private final ProjectionEngine engine = input -> null;

    @Test
    void sizeBondTent_default_isUnsupported() {
        assertThatThrownBy(() -> engine.sizeBondTent(null, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void replayBondTent_default_isUnsupported() {
        var path = new HistoricalReturnPath(Map.of(), new YearReturns(null, Map.of(), Map.of()));

        assertThatThrownBy(() -> engine.replayBondTent(null, path))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("This projection engine cannot replay a bond tent");
    }
}
