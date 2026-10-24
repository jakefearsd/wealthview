package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.BondTentCoveredYear;
import com.wealthview.core.projection.dto.BondTentReplayOutcome;
import com.wealthview.core.projection.dto.BondTentReplayYear;
import com.wealthview.core.projection.dto.BondTentSizing;

/** Turns one replay pass's rows into the outcome metrics (spec 6.2, Builder's call 8). */
final class ReplayOutcomes {

    private static final int BALANCE_AGE = 89;
    private static final int MONEY_SCALE = 2;

    private ReplayOutcomes() {
    }

    /**
     * The outcome of one replay pass named {@code name}, from its yearly rows.
     *
     * @param target the mix's tent size; {@code null} for the no-tent pass, whose tent fields stay null
     * @param sizing the first pass's sizing, which names the retired years and the planned withdrawals
     */
    // PMD.ShortMethodName: "of" is the conventional name for a static factory; renaming it would only obscure it.
    @SuppressWarnings("PMD.ShortMethodName")
    static BondTentReplayOutcome of(String name, @Nullable BigDecimal target, List<BondTentReplayYear> rows,
                                    @Nullable BondTentSizing sizing, int birthYear) {
        var covered = sizing == null ? List.<BondTentCoveredYear>of() : sizing.coveredYears();
        var retiredRows = retiredRows(rows, covered);
        BigDecimal sold = rows.stream().map(BondTentReplayYear::stocksSoldWhileDown)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal yearsCovered = null;
        BigDecimal checkpoint = null;
        if (target != null && sizing != null) {
            int window = Math.min((int) Math.ceil(sizing.coverageYears().doubleValue()), retiredRows.size());
            yearsCovered = yearsCovered(retiredRows, covered, window);
            checkpoint = checkpoint(rows, retiredRows, window, birthYear + BondTentReplayOutcome.TENT_CHECKPOINT_AGE);
        }
        return new BondTentReplayOutcome(name, target, lowest(retiredRows.isEmpty() ? rows : retiredRows), sold,
                yearsCovered, checkpoint, balanceAt90(rows, birthYear), rows);
    }

    private static List<BondTentReplayYear> retiredRows(List<BondTentReplayYear> rows,
                                                        List<BondTentCoveredYear> covered) {
        if (covered.isEmpty()) {
            return List.of();
        }
        int firstRetired = covered.getFirst().year();
        return rows.stream().filter(r -> r.year() >= firstRetired).toList();
    }

    private static BigDecimal lowest(List<BondTentReplayYear> rows) {
        return rows.stream().map(BondTentReplayYear::balance).min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal yearsCovered(List<BondTentReplayYear> retiredRows, List<BondTentCoveredYear> covered,
                                           int window) {
        var planned = new double[window];
        double drawnShares = 0.0;
        for (int k = 0; k < window; k++) {
            // Safe: pass one and the replay share the horizon, so the window never exceeds coveredYears.
            planned[k] = covered.get(k).withdrawal().doubleValue();
            drawnShares += TentCoverage.drawnShare(retiredRows.get(k).tentDrawn().doubleValue(), planned[k]);
        }
        double left = window == 0 ? 0.0 : retiredRows.get(window - 1).tentBalance().doubleValue();
        double years = drawnShares + TentCoverage.unusedYears(left, planned);
        return BigDecimal.valueOf(years).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** The tent at the end of the checkpoint year, or of the covered window when that ends later. */
    @Nullable
    private static BigDecimal checkpoint(List<BondTentReplayYear> rows, List<BondTentReplayYear> retiredRows,
                                         int window, int checkpointYear) {
        int year = window == 0 ? checkpointYear : Math.max(checkpointYear, retiredRows.get(window - 1).year());
        return rowOf(rows, year).map(BondTentReplayYear::tentBalance).orElse(null);
    }

    @Nullable
    private static BigDecimal balanceAt90(List<BondTentReplayYear> rows, int birthYear) {
        return rowOf(rows, birthYear + BALANCE_AGE).map(BondTentReplayYear::balance).orElse(null);
    }

    private static Optional<BondTentReplayYear> rowOf(List<BondTentReplayYear> rows, int year) {
        return rows.stream().filter(r -> r.year() == year).findFirst();
    }
}
