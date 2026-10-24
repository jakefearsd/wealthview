package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HistoricalCrashTest {

    @Test
    void fromKey_financialCrisis_startsIn2008AndIsLabelled2007to09() {
        var crash = HistoricalCrash.fromKey("financial_crisis_2008");

        assertThat(crash).contains(HistoricalCrash.FINANCIAL_CRISIS_2008);
        assertThat(crash.orElseThrow().firstYear()).isEqualTo(2008);
        assertThat(crash.orElseThrow().label()).isEqualTo("2007–09");
    }

    @Test
    void fromKey_unknown_isEmpty() {
        assertThat(HistoricalCrash.fromKey("no_such_crash")).isEmpty();
    }
}
