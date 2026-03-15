package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalReturnsTest {

    @Test
    void loadReturns_containsExpectedCount() {
        double[] returns = HistoricalReturns.getReturns();

        assertThat(returns.length).isGreaterThanOrEqualTo(100);
        assertThat(returns.length).isBetween(150, 160);
    }

    @Test
    void loadReturns_valuesInReasonableRange() {
        double[] returns = HistoricalReturns.getReturns();

        for (double r : returns) {
            assertThat(r)
                    .as("Return value should be between -0.50 and +0.60")
                    .isBetween(-0.50, 0.60);
        }
    }

    @Test
    void loadReturns_knownYears_matchExpected() {
        // Spot-check known historical returns
        double[] returns = HistoricalReturns.getReturns();
        int[] years = HistoricalReturns.getYears();

        assertThat(years.length).isEqualTo(returns.length);

        // Find 1929 (Great Depression crash) — should be significantly negative
        int idx1929 = findYearIndex(years, 1929);
        assertThat(idx1929).isGreaterThanOrEqualTo(0);
        assertThat(returns[idx1929]).isLessThan(-0.05);

        // Find 2008 (Financial Crisis) — should be significantly negative
        int idx2008 = findYearIndex(years, 2008);
        assertThat(idx2008).isGreaterThanOrEqualTo(0);
        assertThat(returns[idx2008]).isLessThan(-0.20);

        // Find 2013 (strong bull year) — should be positive
        int idx2013 = findYearIndex(years, 2013);
        assertThat(idx2013).isGreaterThanOrEqualTo(0);
        assertThat(returns[idx2013]).isGreaterThan(0.10);
    }

    @Test
    void size_matchesReturnsLength() {
        assertThat(HistoricalReturns.size()).isEqualTo(HistoricalReturns.getReturns().length);
    }

    private int findYearIndex(int[] years, int year) {
        for (int i = 0; i < years.length; i++) {
            if (years[i] == year) {
                return i;
            }
        }
        return -1;
    }
}
