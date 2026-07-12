package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-contract guard for {@link GuardrailProfileResponse}, T23 item 4.
 *
 * <p>The computed-only disclosure fields ({@code fixedReturnShare}, {@code floorReduced},
 * {@code originalFloorSuccessProbability}, {@code successProbabilityWithRules}, {@code gatedOn})
 * were consolidated from five flat record components into one {@code @JsonUnwrapped} nested
 * {@link GuardrailProfileResponse.Disclosure} record to collapse the constructor-chain sprawl
 * (T16 review addendum). This is a WIRE-COMPATIBILITY-MANDATORY refactor: the JSON shape must stay
 * byte-identical (same flat, top-level snake_case keys) -- exactly the frontend
 * {@code GuardrailProfileResponse} TypeScript interface's documented field set
 * ({@code frontend/src/types/projection.ts}). This test pins that shape independently of the
 * behavior-focused {@code GuardrailControllerTest}/{@code GuardrailProfileResponseTest} tests, the
 * same role {@code ProjectionYearDtoSerializationTest} plays for its own grouped-record refactor.
 */
class GuardrailProfileResponseSerializationTest {

    private static final ObjectMapper SNAKE = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private static final Set<String> SNAKE_KEYS = Set.of(
            "id", "scenario_id", "name", "essential_floor", "terminal_balance_target", "return_mean",
            "trial_count", "confidence_level", "phases", "yearly_spending", "median_final_balance",
            "failure_rate", "success_probability", "percentile10_final", "stale", "created_at",
            "updated_at", "portfolio_floor", "max_annual_adjustment_rate", "phase_blend_years",
            "risk_tolerance", "cash_reserve_years", "cash_return_rate", "conversion_schedule",
            "fixed_return_share", "floor_reduced", "original_floor_success_probability",
            "success_probability_with_rules", "gated_on");

    /** A response with every field populated to a distinct value, so a swapped field is detectable. */
    private GuardrailProfileResponse fullyPopulated() {
        var phase = new GuardrailPhaseInput("Go-Go", 62, 70, 2, new BigDecimal("80000"));
        var yearly = new GuardrailYearlySpending(2035, 62, new BigDecimal("80000"),
                new BigDecimal("70000"), new BigDecimal("90000"), new BigDecimal("50000"),
                new BigDecimal("30000"), new BigDecimal("15000"), new BigDecimal("65000"), "Go-Go");
        var disclosure = new GuardrailProfileResponse.Disclosure(
                new BigDecimal("0.6000"), true, new BigDecimal("0.8200"), new BigDecimal("0.9785"),
                GuardrailProfileResponse.GATED_ON_WITH_RULES);

        return new GuardrailProfileResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "Optimized Plan",
                new BigDecimal("80000"), BigDecimal.ZERO,
                new BigDecimal("0.10"),
                500, new BigDecimal("0.95"),
                List.of(phase), List.of(yearly),
                new BigDecimal("1000000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("500000"),
                false,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"), OffsetDateTime.parse("2026-01-02T00:00:00Z"),
                BigDecimal.ZERO, new BigDecimal("0.10"),
                2, "moderate", 2, new BigDecimal("0.04"),
                null,
                disclosure);
    }

    @Test
    void serialize_snakeCase_isFlatWithExactlyTheDocumentedKeys() throws Exception {
        JsonNode tree = SNAKE.readTree(SNAKE.writeValueAsString(fullyPopulated()));

        assertThat(tree.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrderElementsOf(SNAKE_KEYS);
    }

    @Test
    void serialize_snakeCase_carriesEveryDisclosureFieldAtTopLevel() throws Exception {
        JsonNode t = SNAKE.readTree(SNAKE.writeValueAsString(fullyPopulated()));

        assertThat(t.get("fixed_return_share").decimalValue()).isEqualByComparingTo("0.6000");
        assertThat(t.get("floor_reduced").booleanValue()).isTrue();
        assertThat(t.get("original_floor_success_probability").decimalValue()).isEqualByComparingTo("0.8200");
        assertThat(t.get("success_probability_with_rules").decimalValue()).isEqualByComparingTo("0.9785");
        assertThat(t.get("gated_on").asText()).isEqualTo("with_rules");
    }

    @Test
    void serialize_nullDisclosure_stillEmitsEveryDisclosureKeyWithDefaults() throws Exception {
        // T23 item 4's never-null-group invariant: passing a null Disclosure group must not omit
        // its keys -- it defaults to Disclosure.empty() and still emits all five, matching
        // ProjectionYearDto's own never-null-group pattern for @JsonUnwrapped groups.
        var minimal = new GuardrailProfileResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Plan",
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("0.07"),
                500, new BigDecimal("0.85"), List.of(), List.of(),
                new BigDecimal("1000000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("500000"), false,
                OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 2, "moderate", 2, new BigDecimal("0.04"), null,
                null);

        JsonNode t = SNAKE.readTree(SNAKE.writeValueAsString(minimal));

        assertThat(t.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrderElementsOf(SNAKE_KEYS);
        assertThat(t.get("fixed_return_share").isNull()).isTrue();
        assertThat(t.get("floor_reduced").booleanValue()).isFalse();
        assertThat(t.get("original_floor_success_probability").isNull()).isTrue();
        assertThat(t.get("success_probability_with_rules").isNull()).isTrue();
        assertThat(t.get("gated_on").asText()).isEqualTo(GuardrailProfileResponse.GATED_ON_NO_ADAPTATION);
    }

    @Test
    void serialize_snakeCase_keepsNestedListsAsContainers() throws Exception {
        JsonNode t = SNAKE.readTree(SNAKE.writeValueAsString(fullyPopulated()));

        assertThat(t.get("phases").isArray()).isTrue();
        assertThat(t.get("phases").get(0).get("name").asText()).isEqualTo("Go-Go");
        assertThat(t.get("yearly_spending").isArray()).isTrue();
        assertThat(t.get("yearly_spending").get(0).get("phase_name").asText()).isEqualTo("Go-Go");
    }
}
