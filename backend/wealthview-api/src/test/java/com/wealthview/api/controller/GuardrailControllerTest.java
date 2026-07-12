package com.wealthview.api.controller;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.GuardrailProfileService;
import com.wealthview.core.projection.dto.GuardrailOptimizationRequest;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import tools.jackson.databind.ObjectMapper;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GuardrailController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class GuardrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GuardrailProfileService guardrailProfileService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SessionStateValidator sessionStateValidator;

    private static final UUID SCENARIO_ID = UUID.randomUUID();

    private GuardrailProfileResponse sampleResponse() {
        return new GuardrailProfileResponse(
                UUID.randomUUID(), SCENARIO_ID, "Optimized Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"),
                5000, new BigDecimal("0.95"),
                List.of(new GuardrailPhaseInput("Early", 62, 72, 3)),
                List.of(new GuardrailYearlySpending(
                        2030, 62, new BigDecimal("75000"), new BigDecimal("62000"),
                        new BigDecimal("91000"), new BigDecimal("30000"),
                        new BigDecimal("45000"), new BigDecimal("12000"),
                        new BigDecimal("63000"), "Early")),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
    }

    @Test
    void optimize_validRequest_returns200() throws Exception {
        when(guardrailProfileService.optimize(eq(TENANT_ID), eq(SCENARIO_ID),
                any(GuardrailOptimizationRequest.class)))
                .thenReturn(sampleResponse());

        var request = new GuardrailOptimizationRequest(
                SCENARIO_ID, "Optimized Plan", new BigDecimal("30000"),
                BigDecimal.ZERO, null, null, null,
                List.of(new GuardrailPhaseInput("Early", 62, 72, 3)),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Optimized Plan"))
                .andExpect(jsonPath("$.yearly_spending").isArray())
                .andExpect(jsonPath("$.yearly_spending[0].age").value(62));
    }

    @Test
    void optimize_scenarioNotFound_returns404() throws Exception {
        when(guardrailProfileService.optimize(eq(TENANT_ID), eq(SCENARIO_ID),
                any(GuardrailOptimizationRequest.class)))
                .thenThrow(new EntityNotFoundException("Scenario not found"));

        var request = new GuardrailOptimizationRequest(
                SCENARIO_ID, "Plan", new BigDecimal("30000"),
                BigDecimal.ZERO, null, null, null, List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // === T18a-5c: trialCount [100, 50000] / confidenceLevel [0.5, 0.999] bounds ===

    @Test
    void optimize_trialCountBelowMinimum_returns400() throws Exception {
        var request = new GuardrailOptimizationRequest(
                SCENARIO_ID, "Plan", new BigDecimal("30000"),
                BigDecimal.ZERO, null, 99, null, List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void optimize_trialCountAboveMaximum_returns400() throws Exception {
        var request = new GuardrailOptimizationRequest(
                SCENARIO_ID, "Plan", new BigDecimal("30000"),
                BigDecimal.ZERO, null, 50001, null, List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optimize_confidenceLevelBelowMinimum_returns400() throws Exception {
        var request = new GuardrailOptimizationRequest(
                SCENARIO_ID, "Plan", new BigDecimal("30000"),
                BigDecimal.ZERO, null, null, new BigDecimal("0.4"), List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optimize_confidenceLevelAtOrAboveOne_returns400() throws Exception {
        var request = new GuardrailOptimizationRequest(
                SCENARIO_ID, "Plan", new BigDecimal("30000"),
                BigDecimal.ZERO, null, null, new BigDecimal("1.0"), List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optimize_trialCountAndConfidenceLevelWithinBounds_returns200() throws Exception {
        // Direction pin: valid boundary values (unlike the base validRequest test, which leaves
        // both null) must NOT be rejected.
        when(guardrailProfileService.optimize(eq(TENANT_ID), eq(SCENARIO_ID),
                any(GuardrailOptimizationRequest.class)))
                .thenReturn(sampleResponse());

        var request = new GuardrailOptimizationRequest(
                SCENARIO_ID, "Plan", new BigDecimal("30000"),
                BigDecimal.ZERO, null, 5000, new BigDecimal("0.95"), List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // === T24: gate_on_adaptive_rules / gated_on wire names (snake_case) ===

    /**
     * Pins the T24 fields' SNAKE_CASE wire names through the real controller + globally configured
     * ObjectMapper path: a raw-JSON request body's {@code gate_on_adaptive_rules} must bind to
     * {@link GuardrailOptimizationRequest#gateOnAdaptiveRules()}, and the response's
     * {@code gatedOn} record component must serialize as {@code gated_on}. Raw JSON on the request
     * side (not {@code objectMapper.writeValueAsString}) so a naming-strategy regression cannot
     * cancel itself out symmetrically.
     */
    @Test
    void optimize_gateOnAdaptiveRulesWireNames_bindRequestAndSerializeResponseSnakeCase() throws Exception {
        var gatedResponse = new GuardrailProfileResponse(
                UUID.randomUUID(), SCENARIO_ID, "Optimized Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"),
                5000, new BigDecimal("0.80"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, new BigDecimal("0.10"), 0, null, 2, new BigDecimal("0.04"), null,
                new GuardrailProfileResponse.Disclosure(null, false, null, new BigDecimal("0.8000"),
                        GuardrailProfileResponse.GATED_ON_WITH_RULES));
        var requestCaptor = ArgumentCaptor.forClass(GuardrailOptimizationRequest.class);
        when(guardrailProfileService.optimize(eq(TENANT_ID), eq(SCENARIO_ID), requestCaptor.capture()))
                .thenReturn(gatedResponse);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Optimized Plan",
                                  "essential_floor": 30000,
                                  "gate_on_adaptive_rules": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gated_on").value("with_rules"))
                .andExpect(jsonPath("$.success_probability_with_rules").value(0.8000));

        assertThat(requestCaptor.getValue().gateOnAdaptiveRules()).isTrue();
    }

    /** Explicit-false anchor over the wire: {@code "gate_on_adaptive_rules": false} must bind as
     * Boolean.FALSE (not null), preserving the conservative no-adaptation-gate opt-out post-V077. */
    @Test
    void optimize_gateOnAdaptiveRulesFalseOnWire_bindsExplicitFalse() throws Exception {
        var requestCaptor = ArgumentCaptor.forClass(GuardrailOptimizationRequest.class);
        when(guardrailProfileService.optimize(eq(TENANT_ID), eq(SCENARIO_ID), requestCaptor.capture()))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Plan",
                                  "essential_floor": 30000,
                                  "gate_on_adaptive_rules": false
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(requestCaptor.getValue().gateOnAdaptiveRules()).isFalse();
    }

    @Test
    void getGuardrail_exists_returns200() throws Exception {
        when(guardrailProfileService.getGuardrailProfile(TENANT_ID, SCENARIO_ID))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/projections/{scenarioId}/guardrail", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Optimized Plan"))
                .andExpect(jsonPath("$.essential_floor").value(30000));
    }

    @Test
    void getGuardrail_notFound_returns404() throws Exception {
        when(guardrailProfileService.getGuardrailProfile(TENANT_ID, SCENARIO_ID))
                .thenThrow(new EntityNotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/projections/{scenarioId}/guardrail", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteGuardrail_exists_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/projections/{scenarioId}/guardrail", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());

        verify(guardrailProfileService).deleteGuardrailProfile(TENANT_ID, SCENARIO_ID);
    }

    @Test
    void deleteGuardrail_notFound_returns404() throws Exception {
        doThrow(new EntityNotFoundException("Not found"))
                .when(guardrailProfileService).deleteGuardrailProfile(TENANT_ID, SCENARIO_ID);

        mockMvc.perform(delete("/api/v1/projections/{scenarioId}/guardrail", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void reoptimize_exists_returns200() throws Exception {
        when(guardrailProfileService.reoptimize(TENANT_ID, SCENARIO_ID))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/guardrail/reoptimize", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Optimized Plan"));
    }
}
