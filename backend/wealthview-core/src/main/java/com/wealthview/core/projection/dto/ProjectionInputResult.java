package com.wealthview.core.projection.dto;

import java.util.List;

/**
 * The built {@link ProjectionInput} for a scenario, plus the distinct union (across every linked
 * account) of holding symbols that had neither a tenant override nor a seed classification and so
 * defaulted to the US_STOCK asset class. Input metadata for the UI to prompt manual
 * reclassification — not projection math, and never included in the engine's
 * {@link ProjectionResultResponse}.
 */
public record ProjectionInputResult(ProjectionInput input, List<String> unclassifiedSymbols) {
}
