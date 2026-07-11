package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.dto.TierBasedSpendingPlan;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses scenario {@code params_json} blobs and spending-profile tier JSON into
 * strongly-typed value objects. Extracted from {@link DeterministicProjectionEngine}
 * to isolate JSON-handling concerns from the projection algorithm.
 */
final class ScenarioParamsParser {

    private static final Logger log = LoggerFactory.getLogger(ScenarioParamsParser.class);

    /** Annual qualified-dividend yield assumed for the taxable pool when a scenario doesn't set one. */
    static final BigDecimal DEFAULT_DIVIDEND_YIELD = new BigDecimal("0.018");

    /**
     * Annual all-in investment fee/expense-ratio drag (expense ratios + advisory) assumed when a
     * scenario doesn't set one — audit B1: no fee concept existed anywhere, so CMA returns were
     * gross index returns; at typical 0.3-0.8% blended cost, 30-year sustainable spending was
     * overstated ~4-10%. Subtracted uniformly from every pool's per-year REAL return in both
     * engines (allocation-derived and fixed-override returns alike).
     */
    static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0025");

    private final ObjectMapper objectMapper = new ObjectMapper();

    ScenarioParams parseParams(@Nullable String paramsJson) {
        return ScenarioParams.parseOrEmpty(objectMapper, paramsJson);
    }

    ScenarioParams defaultParams() {
        return ScenarioParams.EMPTY;
    }

    /** Resolves the effective dividend yield, defaulting to {@link #DEFAULT_DIVIDEND_YIELD} when unset. */
    BigDecimal dividendYield(ScenarioParams params) {
        return params.dividendYield() != null ? params.dividendYield() : DEFAULT_DIVIDEND_YIELD;
    }

    /** Resolves the effective fee rate, defaulting to {@link #DEFAULT_FEE_RATE} when unset. */
    BigDecimal feeRate(ScenarioParams params) {
        return params.feeRate() != null ? params.feeRate() : DEFAULT_FEE_RATE;
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
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("Failed to parse spending_tiers", e);
        }

        return TierBasedSpendingPlan.of(profile.essentialExpenses(), profile.discretionaryExpenses(), tiers);
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
