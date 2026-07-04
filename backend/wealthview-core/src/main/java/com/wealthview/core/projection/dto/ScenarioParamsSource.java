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
}
