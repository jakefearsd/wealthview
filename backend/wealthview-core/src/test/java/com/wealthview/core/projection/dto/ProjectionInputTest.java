package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises ProjectionInput's four convenience constructors. Each "shorter" constructor
 * should delegate to the canonical 12-arg constructor, defaulting the omitted fields to
 * null / empty list.
 */
class ProjectionInputTest {

    private static final UUID SCENARIO_ID = UUID.randomUUID();
    private static final LocalDate RETIRE = LocalDate.of(2035, 1, 1);
    private static final BigDecimal INFLATION = new BigDecimal("0.03");

    @Test
    void eightArgConstructor_defaultsReferenceYearAndCollections() {
        var input = new ProjectionInput(
                SCENARIO_ID, "Base Plan", RETIRE, 90, INFLATION, "{}",
                List.of(), null);

        assertThat(input.scenarioId()).isEqualTo(SCENARIO_ID);
        assertThat(input.scenarioName()).isEqualTo("Base Plan");
        assertThat(input.referenceYear()).isNull();
        assertThat(input.incomeSources()).isEmpty();
        assertThat(input.guardrailSpending()).isNull();
        assertThat(input.properties()).isEmpty();
    }

    @Test
    void nineArgConstructor_populatesReferenceYearAndDefaultsTheRest() {
        var input = new ProjectionInput(
                SCENARIO_ID, "With Ref", RETIRE, 90, INFLATION, "{}",
                List.of(), null, 2025);

        assertThat(input.referenceYear()).isEqualTo(2025);
        assertThat(input.incomeSources()).isEmpty();
        assertThat(input.guardrailSpending()).isNull();
        assertThat(input.properties()).isEmpty();
    }

    @Test
    void tenArgConstructor_includesIncomeSourcesAndDefaultsGuardrailAndProperties() {
        List<ProjectionIncomeSourceInput> sources = List.of();

        var input = new ProjectionInput(
                SCENARIO_ID, "With Incomes", RETIRE, 90, INFLATION, "{}",
                List.of(), null, 2025, sources);

        assertThat(input.incomeSources()).isSameAs(sources);
        assertThat(input.guardrailSpending()).isNull();
        assertThat(input.properties()).isEmpty();
    }

    @Test
    void elevenArgConstructor_includesGuardrailAndDefaultsProperties() {
        GuardrailSpendingInput guardrail = new GuardrailSpendingInput(List.of(), null);

        var input = new ProjectionInput(
                SCENARIO_ID, "With Guardrail", RETIRE, 90, INFLATION, "{}",
                List.of(), null, 2025, List.of(), guardrail);

        assertThat(input.guardrailSpending()).isSameAs(guardrail);
        assertThat(input.properties()).isEmpty();
    }
}
