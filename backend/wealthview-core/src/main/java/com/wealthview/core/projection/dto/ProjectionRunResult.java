package com.wealthview.core.projection.dto;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * The deterministic engine's projection result for a single scenario run, plus the distinct
 * union of linked-account holding symbols that fell back to a default classification for lack of
 * a tenant override or seed entry (see {@link ProjectionInputResult}), plus any non-fatal
 * run-level warnings (e.g. an unsupported filing state, audit C3), plus (Phase 1a) the per-year
 * tax space and the after-tax legacy value. Kept separate from {@link ProjectionResultResponse}
 * — which the projection golden files pin byte-for-byte — since none of these change the
 * projection math.
 */
public record ProjectionRunResult(ProjectionResultResponse result, List<String> unclassifiedSymbols,
                                   List<String> warnings, List<TaxSpaceYear> taxSpace,
                                   @Nullable TerminalValue terminalValue) {

    /** Back-compat for callers that predate run-level warnings (audit C3): defaults to none. */
    public ProjectionRunResult(ProjectionResultResponse result, List<String> unclassifiedSymbols) {
        this(result, unclassifiedSymbols, List.of(), List.of(), null);
    }

    /** Back-compat for callers that predate Phase 1a tax space / legacy value: defaults to none. */
    public ProjectionRunResult(ProjectionResultResponse result, List<String> unclassifiedSymbols,
                               List<String> warnings) {
        this(result, unclassifiedSymbols, warnings, List.of(), null);
    }
}
