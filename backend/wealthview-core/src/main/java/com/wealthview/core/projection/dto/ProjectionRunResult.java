package com.wealthview.core.projection.dto;

import java.util.List;

/**
 * The deterministic engine's projection result for a single scenario run, plus the distinct
 * union of linked-account holding symbols that fell back to a default classification for lack of
 * a tenant override or seed entry (see {@link ProjectionInputResult}). Kept separate from
 * {@link ProjectionResultResponse} — which the projection golden files pin byte-for-byte — since
 * the unclassified-symbol list is UI-facing input metadata, not projection math.
 */
public record ProjectionRunResult(ProjectionResultResponse result, List<String> unclassifiedSymbols) {
}
