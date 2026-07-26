package com.wealthview.core.testutil;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.lang.Nullable;

import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;

/**
 * Task 19: object-mother factory methods for {@link ProjectionScenarioEntity}, seeded with the two
 * de-facto canonical fixtures shared across {@code ScenarioCrudServiceTest},
 * {@code ProjectionInputBuilderTest}, {@code ProjectionServiceTest}, and
 * {@code GuardrailProfileServiceTest}: name "Plan", retirement date 2055-01-01, end age 90, 3%
 * inflation (the deterministic-engine fixture family) and name/paramsJson-parameterized retirement
 * date 2030-01-01, end age 90, 3% inflation (the guardrail/Monte-Carlo fixture family, dominant in
 * {@code GuardrailProfileServiceTest}). Derived from the dominant literal construction across all
 * four files' raw {@code new ProjectionScenarioEntity(...)} call sites (124 total; 116 match one of
 * these two shapes).
 *
 * <p>Sites that vary the name AND/OR any of retirement date / end age / inflation rate beyond what
 * these two factories expose are left as raw constructions -- see the Task 19 report for the list.
 */
public final class ScenarioMother {

    private ScenarioMother() {
    }

    /** The deterministic-engine fixture: "Plan", 2055-01-01, end age 90, 3% inflation, no params. */
    public static ProjectionScenarioEntity scenario(TenantEntity tenant) {
        return new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90, new BigDecimal("0.03"), null);
    }

    /** Same as {@link #scenario(TenantEntity)} but with an explicit {@code params_json} blob. */
    public static ProjectionScenarioEntity scenarioWithParams(TenantEntity tenant, @Nullable String paramsJson) {
        return new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90, new BigDecimal("0.03"), paramsJson);
    }

    /**
     * The guardrail/Monte-Carlo fixture: retirement date 2030-01-01, end age 90, 3% inflation --
     * dominant across {@code GuardrailProfileServiceTest} (43 of its 45 constructions), where name
     * and {@code params_json} are the only components that vary per test.
     */
    public static ProjectionScenarioEntity guardrailScenario(
            TenantEntity tenant, String name, @Nullable String paramsJson) {
        return new ProjectionScenarioEntity(
                tenant, name, LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), paramsJson);
    }
}
