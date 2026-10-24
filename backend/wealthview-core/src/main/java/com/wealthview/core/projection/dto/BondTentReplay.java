package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A scenario replayed through one historical crash, with and without its bond tent.
 *
 * @param crashLabel       the named crash's label, or the start year as text
 * @param crashFirstYear   the first history year replayed
 * @param crashLastYear    the last history year of the crash window
 * @param crashCalendarYear the calendar year the crash's first year lands in
 * @param offset           years from the anchor (retirement, or today when already retired) to the crash
 * @param retirementYear   the anchor the offset is measured from, never earlier than the current year
 * @param plannedYears     the tent's configured coverage in years
 * @param checkpointAge    the age at which the tent left over is reported
 * @param availableOffsets the offsets that keep the crash from landing before today
 * @param firstStartYear   the first history year a start year may name
 * @param lastStartYear    the last history year a start year may name
 * @param outcomes         the no-tent column followed by each mix
 */
public record BondTentReplay(
        String crashLabel,
        int crashFirstYear,
        int crashLastYear,
        int crashCalendarYear,
        int offset,
        int retirementYear,
        BigDecimal plannedYears,
        int checkpointAge,
        List<Integer> availableOffsets,
        int firstStartYear,
        int lastStartYear,
        List<BondTentReplayOutcome> outcomes) {
}
