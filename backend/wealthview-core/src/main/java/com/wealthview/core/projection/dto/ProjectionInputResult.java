package com.wealthview.core.projection.dto;

import java.util.List;

/**
 * The built {@link ProjectionInput} for a scenario, plus the distinct union (across every linked
 * account) of holding symbols that had neither a tenant override nor a seed classification and so
 * defaulted to the US_STOCK asset class, plus input-level warnings about data the projection could
 * not model (e.g. a linked rental's mortgage balance with no loan details to amortize). Input
 * metadata for the UI — not projection math, and never included in the engine's
 * {@link ProjectionResultResponse}.
 */
public record ProjectionInputResult(ProjectionInput input, List<String> unclassifiedSymbols,
                                    List<String> warnings) {

    /** Back-compat for callers that predate input-level warnings: defaults to none. */
    public ProjectionInputResult(ProjectionInput input, List<String> unclassifiedSymbols) {
        this(input, unclassifiedSymbols, List.of());
    }
}
