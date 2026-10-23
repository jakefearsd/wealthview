package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A scenario's bond tent: a reserve of bonds sized to cover the first {@code coverageYears} of
 * retirement withdrawals, built up over {@code buildUpStartYears} before retirement. Stored as the
 * nested {@code bond_tent} object of {@code params_json}.
 *
 * <p>{@code mixes} holds one to three named maturity splits; the first is the plan mix the
 * projection uses. {@code yields} are the nominal annual yields per maturity.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BondTentConfig(
        BigDecimal coverageYears,
        Integer buildUpStartYears,
        List<BondTentMix> mixes,
        BondMaturityValues yields) {

    /** The mix the projection runs: the first one. */
    public BondTentMix planMix() {
        return mixes.getFirst();
    }
}
