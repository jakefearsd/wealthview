package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The strongly-typed contents of a scenario's {@code params_json} column — the
 * single source of truth for its field set and snake_case wire keys. Both the
 * write side (ScenarioCrudService serializing a request) and every read side
 * (the projection engine, guardrail optimization) go through this record, so a
 * new scenario parameter is one added component here instead of coordinated
 * edits across hand-written key literals.
 *
 * <p>{@code withdrawalOrder} stays a string to preserve the persisted format
 * (lowercase tokens); use {@link #resolvedWithdrawalOrder()} for the enum view.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioParams(
        Integer birthYear,
        BigDecimal withdrawalRate,
        String withdrawalStrategy,
        BigDecimal dynamicCeiling,
        BigDecimal dynamicFloor,
        String filingStatus,
        BigDecimal otherIncome,
        BigDecimal annualRothConversion,
        String withdrawalOrder,
        BigDecimal dynamicSequencingBracketRate,
        String rothConversionStrategy,
        BigDecimal targetBracketRate,
        Integer rothConversionStartYear,
        String state,
        BigDecimal primaryResidencePropertyTax,
        BigDecimal primaryResidenceMortgageInterest,
        BigDecimal dividendYield) {

    private static final Logger log = LoggerFactory.getLogger(ScenarioParams.class);

    public static final ScenarioParams EMPTY = new ScenarioParams(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    public static ScenarioParams from(ScenarioParamsSource source) {
        return new ScenarioParams(
                source.birthYear(), source.withdrawalRate(), source.withdrawalStrategy(),
                source.dynamicCeiling(), source.dynamicFloor(), source.filingStatus(),
                source.otherIncome(), source.annualRothConversion(), source.withdrawalOrder(),
                source.dynamicSequencingBracketRate(), source.rothConversionStrategy(),
                source.targetBracketRate(), source.rothConversionStartYear(), source.state(),
                source.primaryResidencePropertyTax(), source.primaryResidenceMortgageInterest(),
                source.dividendYield());
    }

    /**
     * Parses a persisted {@code params_json} blob; null, blank, or malformed
     * input yields {@link #EMPTY} (a scenario without parameters), matching the
     * lenient behavior every reader has always had.
     */
    public static ScenarioParams parseOrEmpty(ObjectMapper mapper, @Nullable String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return EMPTY;
        }
        try {
            return mapper.readValue(paramsJson, ScenarioParams.class);
        } catch (JacksonException e) {
            log.warn("Failed to parse params_json; treating scenario as parameterless", e);
            return EMPTY;
        }
    }

    /** Serializes to the persisted snake_case form, or null when every field is null. */
    @Nullable
    public String toJson(ObjectMapper mapper) {
        if (equals(EMPTY)) {
            return null;
        }
        try {
            return mapper.writeValueAsString(this);
        } catch (JacksonException e) {
            throw new IllegalStateException("ScenarioParams serialization failed", e);
        }
    }

    public WithdrawalOrder resolvedWithdrawalOrder() {
        return WithdrawalOrder.fromString(withdrawalOrder);
    }
}
