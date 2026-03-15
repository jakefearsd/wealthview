package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.GuardrailProfileService;
import com.wealthview.core.projection.dto.GuardrailOptimizationRequest;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
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
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class GuardrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GuardrailProfileService guardrailProfileService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID SCENARIO_ID = UUID.randomUUID();

    private GuardrailProfileResponse sampleResponse() {
        return new GuardrailProfileResponse(
                UUID.randomUUID(), SCENARIO_ID, "Optimized Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                5000, new BigDecimal("0.95"),
                List.of(new GuardrailPhaseInput("Early", 62, 72, 3)),
                List.of(new GuardrailYearlySpending(
                        2030, 62, new BigDecimal("75000"), new BigDecimal("62000"),
                        new BigDecimal("91000"), new BigDecimal("30000"),
                        new BigDecimal("45000"), new BigDecimal("12000"),
                        new BigDecimal("63000"), "Early")),
                new BigDecimal("250000"), new BigDecimal("0.05"),
                new BigDecimal("100000"), new BigDecimal("500000"),
                false, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void optimize_validRequest_returns200() throws Exception {
        when(guardrailProfileService.optimize(eq(TENANT_ID), eq(SCENARIO_ID),
                any(GuardrailOptimizationRequest.class)))
                .thenReturn(sampleResponse());

        var request = new GuardrailOptimizationRequest(
                SCENARIO_ID, "Optimized Plan", new BigDecimal("30000"),
                BigDecimal.ZERO, null, null, null, null,
                List.of(new GuardrailPhaseInput("Early", 62, 72, 3)),
                null, null, null, null,
                null, null);

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
                BigDecimal.ZERO, null, null, null, null, List.of(),
                null, null, null, null,
                null, null);

        mockMvc.perform(post("/api/v1/projections/{scenarioId}/optimize", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
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
