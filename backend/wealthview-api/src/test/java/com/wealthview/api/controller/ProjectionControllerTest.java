package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.ProjectionService;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.ScenarioResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class ProjectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProjectionService projectionService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID SCENARIO_ID = UUID.randomUUID();

    private ScenarioResponse sampleScenario() {
        return new ScenarioResponse(
                SCENARIO_ID, "Retirement Plan",
                LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.0300"), null,
                List.of(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void create_validInput_returns201() throws Exception {
        when(projectionService.createScenario(eq(TENANT_ID), any()))
                .thenReturn(sampleScenario());

        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Retirement Plan",
                                    "retirement_date": "2055-01-01",
                                    "end_age": 90,
                                    "inflation_rate": 0.03,
                                    "birth_year": 1990,
                                    "accounts": [{
                                        "initial_balance": 100000,
                                        "annual_contribution": 10000,
                                        "expected_return": 0.07
                                    }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Retirement Plan"))
                .andExpect(jsonPath("$.end_age").value(90));
    }

    @Test
    void list_authenticated_returns200() throws Exception {
        when(projectionService.listScenarios(TENANT_ID))
                .thenReturn(List.of(sampleScenario()));

        mockMvc.perform(get("/api/v1/projections")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Retirement Plan"));
    }

    @Test
    void get_existingScenario_returns200() throws Exception {
        when(projectionService.getScenario(TENANT_ID, SCENARIO_ID))
                .thenReturn(sampleScenario());

        mockMvc.perform(get("/api/v1/projections/{id}", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retirement Plan"));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        when(projectionService.getScenario(TENANT_ID, SCENARIO_ID))
                .thenThrow(new EntityNotFoundException("Scenario not found"));

        mockMvc.perform(get("/api/v1/projections/{id}", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_existingScenario_returns204() throws Exception {
        doNothing().when(projectionService).deleteScenario(TENANT_ID, SCENARIO_ID);

        mockMvc.perform(delete("/api/v1/projections/{id}", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void run_existingScenario_returns200() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(new ProjectionYearDto(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(result);

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario_id").value(SCENARIO_ID.toString()))
                .andExpect(jsonPath("$.yearly_data[0].year").value(2026))
                .andExpect(jsonPath("$.final_balance").value(117700));
    }
}
