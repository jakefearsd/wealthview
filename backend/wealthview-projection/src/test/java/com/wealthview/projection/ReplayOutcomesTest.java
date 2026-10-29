package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.BondTentCoveredYear;
import com.wealthview.core.projection.dto.BondTentReplayYear;
import com.wealthview.core.projection.dto.BondTentSizing;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayOutcomesTest {

    private static final int BIRTH_YEAR = 1970;

    private static BondTentReplayYear row(int year, double balance, double tent, double drawn, double soldDown) {
        return new BondTentReplayYear(year, year - BIRTH_YEAR, BigDecimal.valueOf(balance), BigDecimal.valueOf(tent),
                BigDecimal.valueOf(drawn), BigDecimal.valueOf(soldDown), false, null);
    }

    private static BondTentSizing sizing(double coverageYears, int firstYear, double... withdrawals) {
        var covered = new ArrayList<BondTentCoveredYear>();
        for (int i = 0; i < withdrawals.length; i++) {
            covered.add(new BondTentCoveredYear(firstYear + i, BigDecimal.valueOf(withdrawals[i]), BigDecimal.ONE));
        }
        return new BondTentSizing(BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(coverageYears), List.of(), covered);
    }

    @Test
    void of_noTentPass_leavesTentMetricsNullAndTakesLowestOverAllRows() {
        var rows = List.of(row(2030, 900, 0, 0, 5), row(2031, 700, 0, 0, 7), row(2032, 800, 0, 0, 0));

        var outcome = ReplayOutcomes.of("none", null, rows, sizing(2, 2031, 100, 100), BIRTH_YEAR);

        assertThat(outcome.tentTarget()).isNull();
        assertThat(outcome.yearsCovered()).isNull();
        assertThat(outcome.tentLeftAtCheckpoint()).isNull();
        assertThat(outcome.lowestBalance()).isEqualByComparingTo("700.00");
        assertThat(outcome.stocksSoldWhileDown()).isEqualByComparingTo("12");
    }

    @Test
    void of_withTentAndNoRetiredYears_usesAllRowsForLowestAndZeroCoverage() {
        var rows = List.of(row(2030, 900, 50, 0, 0), row(2031, 600, 40, 0, 0));
        var empty = new BondTentSizing(BigDecimal.TEN, BigDecimal.valueOf(3), List.of(), List.of());

        var outcome = ReplayOutcomes.of("mix", BigDecimal.valueOf(50), rows, empty, BIRTH_YEAR);

        assertThat(outcome.lowestBalance()).isEqualByComparingTo("600");
        assertThat(outcome.yearsCovered()).isEqualByComparingTo("0");
    }

    @Test
    void of_withTent_countsDrawnSharesPlusUnusedTentInYears() {
        var rows = List.of(row(2030, 1000, 300, 0, 0), row(2031, 950, 250, 50, 0), row(2032, 900, 100, 100, 0));
        var sizing = sizing(2, 2031, 100, 100);

        var outcome = ReplayOutcomes.of("mix", BigDecimal.valueOf(300), rows, sizing, BIRTH_YEAR);

        // drawn shares 0.5 + 1.0, tent left 100 over a mean planned withdrawal of 100 = 1 year
        assertThat(outcome.yearsCovered()).isEqualByComparingTo("2.50");
        assertThat(outcome.lowestBalance()).isEqualByComparingTo("900");
    }

    @Test
    void of_horizonEndsBeforeCheckpointAndAge90_leavesThoseMetricsNull() {
        var rows = List.of(row(2031, 950, 250, 50, 0), row(2032, 900, 100, 100, 0));

        var outcome = ReplayOutcomes.of("mix", BigDecimal.valueOf(300), rows, sizing(2, 2031, 100, 100), BIRTH_YEAR);

        assertThat(outcome.tentLeftAtCheckpoint()).isNull();
        assertThat(outcome.balanceAt90()).isNull();
    }

    @Test
    void of_rowsReachCheckpointAndAge90_readsTentAtAge70AndBalanceAtAge89() {
        var rows = List.of(row(2031, 950, 250, 50, 0), row(BIRTH_YEAR + 70, 800, 75, 0, 0), row(BIRTH_YEAR + 89, 640, 0, 0, 0));

        var outcome = ReplayOutcomes.of("mix", BigDecimal.valueOf(300), rows, sizing(1, 2031, 100), BIRTH_YEAR);

        assertThat(outcome.balanceAt90()).isEqualByComparingTo("640");
        assertThat(outcome.tentLeftAtCheckpoint()).isEqualByComparingTo("75");
    }
}
