package com.wealthview.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wealthview.core.projection.dto.ProjectionRunResult;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingFeasibilitySummary;

/**
 * Wire response for {@code GET /api/v1/projections/{id}/run}. Mirrors every field of the core
 * engine's {@code ProjectionResultResponse} and adds {@code unclassifiedSymbols} so the UI can
 * prompt for manual reclassification of holdings that defaulted to US_STOCK for lack of a seed
 * or tenant-override classification, plus {@code warnings} for non-fatal run-level notices (e.g.
 * an unsupported filing state, audit C3). Kept as a distinct API-layer type (rather than fields on
 * the core response) so the deterministic-engine golden files never need to change for this
 * UI-facing, input-derived metadata.
 */
public record ProjectionRunResponse(
        UUID scenarioId,
        List<ProjectionYearDto> yearlyData,
        BigDecimal finalBalance,
        int yearsInRetirement,
        SpendingFeasibilitySummary spendingFeasibility,
        BigDecimal finalNetWorth,
        List<String> unclassifiedSymbols,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

    public static ProjectionRunResponse from(ProjectionRunResult runResult) {
        var result = runResult.result();
        return new ProjectionRunResponse(
                result.scenarioId(), result.yearlyData(), result.finalBalance(),
                result.yearsInRetirement(), result.spendingFeasibility(), result.finalNetWorth(),
                runResult.unclassifiedSymbols(), runResult.warnings());
    }
}
