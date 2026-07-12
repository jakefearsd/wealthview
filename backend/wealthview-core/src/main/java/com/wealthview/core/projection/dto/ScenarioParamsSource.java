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
     * Audit C1: the nominal annual coupon proxy assumed for the taxable pool's bond/cash sleeve
     * (see {@code ScenarioParamsParser.DEFAULT_INTEREST_YIELD} for the resolved default when this
     * is {@code null}). Split from {@link #dividendYield()} by the taxable account's own asset
     * allocation — the equity share (us_stock + intl_stock) keeps the qualified-dividend/LTCG
     * treatment {@link #dividendYield()} already had; the bond+cash share is taxed as ORDINARY
     * income at this rate instead.
     */
    BigDecimal interestYield();

    /**
     * Audit C10: opt-in to widen the capital-market assumptions window from the default 1972-2025
     * to the full 1928-2025 seed (adds the Depression-era tail — 1931's ~-38% real equity year —
     * to both the Monte Carlo block bootstrap and the deterministic engine's blended real return).
     * {@code null}/absent means "not set", resolved to {@code false} (the unchanged default window)
     * by {@code ScenarioParamsParser.includeDepressionYears}.
     */
    Boolean includeDepressionYears();
}
