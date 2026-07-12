package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

/**
 * Common accessor interface shared by {@link ScenarioRequest} (create and update share one payload).
 * Allows serialization via {@link ScenarioParams}.
 */
public interface ScenarioParamsSource {

    Integer birthYear();

    BigDecimal withdrawalRate();

    String withdrawalStrategy();

    BigDecimal dynamicCeiling();

    BigDecimal dynamicFloor();

    String filingStatus();

    BigDecimal otherIncome();

    BigDecimal annualRothConversion();

    String withdrawalOrder();

    BigDecimal dynamicSequencingBracketRate();

    String rothConversionStrategy();

    BigDecimal targetBracketRate();

    Integer rothConversionStartYear();

    String state();

    BigDecimal primaryResidencePropertyTax();

    BigDecimal primaryResidenceMortgageInterest();

    BigDecimal dividendYield();

    BigDecimal feeRate();

    /**
     * Audit C10: opt-in to widen the capital-market assumptions window from the default 1972-2025
     * to the full 1928-2025 seed (adds the Depression-era tail — 1931's ~-38% real equity year —
     * to both the Monte Carlo block bootstrap and the deterministic engine's blended real return).
     * {@code null}/absent means "not set", resolved to {@code false} (the unchanged default window)
     * by {@code ScenarioParamsParser.includeDepressionYears}.
     */
    Boolean includeDepressionYears();
}
