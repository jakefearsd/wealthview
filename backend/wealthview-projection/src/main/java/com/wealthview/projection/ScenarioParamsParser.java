package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.dto.TierBasedSpendingPlan;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

/**
 * Parses scenario {@code params_json} blobs and spending-profile tier JSON into
 * strongly-typed value objects. Extracted from {@link DeterministicProjectionEngine}
 * to isolate JSON-handling concerns from the projection algorithm.
 */
final class ScenarioParamsParser {

    private static final Logger log = LoggerFactory.getLogger(ScenarioParamsParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Strongly-typed view of a scenario's {@code params_json} blob. */
    record ScenarioParams(
            Integer birthYear,
            BigDecimal withdrawalRate,
            String withdrawalStrategy,
            BigDecimal dynamicCeiling,
            BigDecimal dynamicFloor,
            String filingStatus,
            BigDecimal otherIncome,
            BigDecimal annualRothConversion,
            WithdrawalOrder withdrawalOrder,
            String rothConversionStrategy,
            BigDecimal targetBracketRate,
            Integer rothConversionStartYear,
            String state,
            BigDecimal primaryResidencePropertyTax,
            BigDecimal primaryResidenceMortgageInterest,
            BigDecimal dynamicSequencingBracketRate) {
    }

    ScenarioParams parseParams(@Nullable String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return defaultParams();
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            WithdrawalOrder withdrawalOrder = node.has("withdrawal_order")
                    ? WithdrawalOrder.fromString(node.get("withdrawal_order").asText())
                    : WithdrawalOrder.TAXABLE_FIRST;
            return new ScenarioParams(
                    parseOptionalInt(node, "birth_year"),
                    parseOptionalBigDecimal(node, "withdrawal_rate"),
                    parseOptionalString(node, "withdrawal_strategy"),
                    parseOptionalBigDecimal(node, "dynamic_ceiling"),
                    parseOptionalBigDecimal(node, "dynamic_floor"),
                    parseOptionalString(node, "filing_status"),
                    parseOptionalBigDecimal(node, "other_income"),
                    parseOptionalBigDecimal(node, "annual_roth_conversion"),
                    withdrawalOrder,
                    parseOptionalString(node, "roth_conversion_strategy"),
                    parseOptionalBigDecimal(node, "target_bracket_rate"),
                    parseOptionalInt(node, "roth_conversion_start_year"),
                    parseOptionalString(node, "state"),
                    parseOptionalBigDecimal(node, "primary_residence_property_tax"),
                    parseOptionalBigDecimal(node, "primary_residence_mortgage_interest"),
                    parseOptionalBigDecimal(node, "dynamic_sequencing_bracket_rate"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException e) {
            log.warn("Failed to parse params_json", e);
            return defaultParams();
        }
    }

    ScenarioParams defaultParams() {
        return new ScenarioParams(null, null, null, null, null, null, null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null, null, null, null, null, null);
    }

    @Nullable
    TierBasedSpendingPlan parseTierBasedPlan(@Nullable SpendingProfileInput profile) {
        if (profile == null) {
            return null;
        }
        List<TierBasedSpendingPlan.SpendingTierData> tiers = List.of();
        try {
            if (profile.spendingTiers() != null && !profile.spendingTiers().isBlank()
                    && !"[]".equals(profile.spendingTiers().trim())) {
                var tierNode = objectMapper.readTree(profile.spendingTiers());
                var tierList = new ArrayList<TierBasedSpendingPlan.SpendingTierData>();
                for (var item : tierNode) {
                    var essExp = getDecimal(item, "essentialExpenses", "essential_expenses", BigDecimal.ZERO);
                    var discExp = getDecimal(item, "discretionaryExpenses", "discretionary_expenses", BigDecimal.ZERO);
                    int startAge = getInt(item, "startAge", "start_age", 0);
                    Integer endAge = getOptionalInt(item, "endAge", "end_age");
                    tierList.add(new TierBasedSpendingPlan.SpendingTierData(
                            getString(item, "name", ""),
                            startAge, endAge, essExp, discExp));
                }
                tiers = tierList;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to parse spending_tiers", e);
        }

        return TierBasedSpendingPlan.of(profile.essentialExpenses(), profile.discretionaryExpenses(), tiers);
    }

    private BigDecimal parseOptionalBigDecimal(JsonNode node, String fieldName) {
        return node.has(fieldName) ? new BigDecimal(node.get(fieldName).asText()) : null;
    }

    private Integer parseOptionalInt(JsonNode node, String fieldName) {
        return node.has(fieldName) ? node.get(fieldName).asInt() : null;
    }

    private String parseOptionalString(JsonNode node, String fieldName) {
        return node.has(fieldName) ? node.get(fieldName).asText() : null;
    }

    private BigDecimal getDecimal(JsonNode item, String camelCase, String snakeCase, BigDecimal fallback) {
        if (item.has(camelCase) && !item.get(camelCase).isNull()) {
            return new BigDecimal(item.get(camelCase).asText());
        } else if (item.has(snakeCase) && !item.get(snakeCase).isNull()) {
            return new BigDecimal(item.get(snakeCase).asText());
        }
        return fallback;
    }

    private int getInt(JsonNode item, String camelCase, String snakeCase, int fallback) {
        if (item.has(camelCase) && !item.get(camelCase).isNull()) {
            return item.get(camelCase).asInt();
        } else if (item.has(snakeCase) && !item.get(snakeCase).isNull()) {
            return item.get(snakeCase).asInt();
        }
        return fallback;
    }

    private Integer getOptionalInt(JsonNode item, String camelCase, String snakeCase) {
        if (item.has(camelCase) && !item.get(camelCase).isNull()) {
            return item.get(camelCase).asInt();
        } else if (item.has(snakeCase) && !item.get(snakeCase).isNull()) {
            return item.get(snakeCase).asInt();
        }
        return null;
    }

    private String getString(JsonNode item, String fieldName, String fallback) {
        if (item.has(fieldName) && !item.get(fieldName).isNull()) {
            return item.get(fieldName).asText();
        }
        return fallback;
    }
}
