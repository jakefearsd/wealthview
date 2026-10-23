package com.wealthview.core.projection.dto;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * The deterministic engine's full run output (Phase 1a): the byte-pinned {@link ProjectionResultResponse}
 * the golden files serialize, plus side-channel data that must never move those goldens -- per-year tax
 * pictures, the derived tax space, and the after-tax legacy value ({@code null} when the projection
 * produced no years), and the bond tent sizing and per-year balances ({@code null} when no tent is active).
 */
public record ProjectionRunDetail(ProjectionResultResponse result, List<YearTaxPicture> taxPictures,
                                  List<TaxSpaceYear> taxSpace, @Nullable TerminalValue terminalValue,
                                  @Nullable BondTentResult bondTent) {

    /** Back-compat for runs without a bond tent: {@code bondTent} is {@code null}. */
    public ProjectionRunDetail(ProjectionResultResponse result, List<YearTaxPicture> taxPictures,
                               List<TaxSpaceYear> taxSpace, @Nullable TerminalValue terminalValue) {
        this(result, taxPictures, taxSpace, terminalValue, null);
    }
}
