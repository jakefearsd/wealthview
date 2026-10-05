package com.wealthview.api.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.ProjectionService;
import com.wealthview.core.projection.ScenarioCrudService;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CompareResponse;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionRunResult;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.ScenarioRequest;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.core.projection.dto.TaxSpaceYear;
import com.wealthview.core.projection.dto.TerminalValue;
import tools.jackson.databind.ObjectMapper;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.errorEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(ProjectionController.class)
class ProjectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ScenarioCrudService scenarioCrudService;

    @MockitoBean
    private ProjectionService projectionService;

    private static final UUID SCENARIO_ID = UUID.randomUUID();

    private ScenarioResponse sampleScenario() {
        return new ScenarioResponse(
                SCENARIO_ID, "Retirement Plan",
                LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.0300"), null,
                List.of(), null, null, List.of(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void create_validInput_returns201() throws Exception {
        when(scenarioCrudService.createScenario(eq(TENANT_ID), any()))
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

    // Phase 1a: birth-month validation lives in ScenarioCrudService and surfaces as a 400 through
    // GlobalExceptionHandler. The stub also proves birth_month binds from the snake_case body.
    @Test
    void create_birthMonthWithoutBirthYear_returns400() throws Exception {
        when(scenarioCrudService.createScenario(eq(TENANT_ID), any())).thenAnswer(inv -> {
            var request = inv.<ScenarioRequest>getArgument(1);
            assertThat(request.birthMonth()).isEqualTo(3);
            throw new IllegalArgumentException("birth_month requires birth_year to be set");
        });

        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Plan", "retirement_date": "2055-01-01", "end_age": 90,
                                 "inflation_rate": 0.03, "birth_month": 3, "accounts": []}
                                """))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("birth_month requires birth_year to be set"));
    }

    @Test
    void create_birthMonthOutOfRange_returns400() throws Exception {
        when(scenarioCrudService.createScenario(eq(TENANT_ID), any())).thenAnswer(inv -> {
            var request = inv.<ScenarioRequest>getArgument(1);
            assertThat(request.birthMonth()).isEqualTo(13);
            throw new IllegalArgumentException("birth_month must be between 1 and 12");
        });

        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Plan", "retirement_date": "2055-01-01", "end_age": 90,
                                 "inflation_rate": 0.03, "birth_year": 1970, "birth_month": 13, "accounts": []}
                                """))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("birth_month must be between 1 and 12"));
    }

    // Phase 1a: heir_tax_rate range validation lives in ScenarioParamsValidator and surfaces as a 400
    // through GlobalExceptionHandler. The stub also proves heir_tax_rate binds from the snake_case body.
    @Test
    void create_heirTaxRateAboveMax_returns400() throws Exception {
        when(scenarioCrudService.createScenario(eq(TENANT_ID), any())).thenAnswer(inv -> {
            var request = inv.<ScenarioRequest>getArgument(1);
            assertThat(request.heirTaxRate()).isEqualByComparingTo("0.51");
            throw new IllegalArgumentException("heir_tax_rate must be between 0 and 0.50");
        });

        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Plan", "retirement_date": "2055-01-01", "end_age": 90,
                                 "inflation_rate": 0.03, "heir_tax_rate": 0.51, "accounts": []}
                                """))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("heir_tax_rate must be between 0 and 0.50"));
    }

    @Test
    void list_authenticated_returns200() throws Exception {
        when(scenarioCrudService.listScenarios(TENANT_ID))
                .thenReturn(List.of(sampleScenario()));

        mockMvc.perform(get("/api/v1/projections")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Retirement Plan"));
    }

    @Test
    void get_existingScenario_returns200() throws Exception {
        when(scenarioCrudService.getScenario(TENANT_ID, SCENARIO_ID))
                .thenReturn(sampleScenario());

        mockMvc.perform(get("/api/v1/projections/{id}", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retirement Plan"));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        when(scenarioCrudService.getScenario(TENANT_ID, SCENARIO_ID))
                .thenThrow(new EntityNotFoundException("Scenario not found"));

        mockMvc.perform(get("/api/v1/projections/{id}", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_existingScenario_returns204() throws Exception {
        doNothing().when(scenarioCrudService).deleteScenario(TENANT_ID, SCENARIO_ID);

        mockMvc.perform(delete("/api/v1/projections/{id}", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void compare_twoScenarios_returns200() throws Exception {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var response = new CompareResponse(List.of(
                new ProjectionResultResponse(id1, List.of(), BigDecimal.ZERO, 0, null),
                new ProjectionResultResponse(id2, List.of(), BigDecimal.ZERO, 0, null)));
        when(projectionService.compareScenarios(eq(TENANT_ID), any(CompareRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/projections/compare")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario_ids": ["%s", "%s"]}
                                """.formatted(id1, id2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results.length()").value(2));
    }

    @Test
    void update_validInput_returns200() throws Exception {
        when(scenarioCrudService.updateScenario(eq(TENANT_ID), eq(SCENARIO_ID), any()))
                .thenReturn(sampleScenario());

        mockMvc.perform(put("/api/v1/projections/{id}", SCENARIO_ID)
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retirement Plan"));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        when(scenarioCrudService.updateScenario(eq(TENANT_ID), eq(SCENARIO_ID), any()))
                .thenThrow(new EntityNotFoundException("Scenario not found"));

        mockMvc.perform(put("/api/v1/projections/{id}", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Plan",
                                    "retirement_date": "2055-01-01",
                                    "end_age": 90,
                                    "inflation_rate": 0.03,
                                    "accounts": []
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_withNullAccounts_returns201AndLogsZero() throws Exception {
        // Covers the `request.accounts() != null ? request.accounts().size() : 0` else-branch in the log statement
        when(scenarioCrudService.createScenario(eq(TENANT_ID), any()))
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
                                    "birth_year": 1990
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Retirement Plan"));
    }

    @Test
    void run_existingScenario_returns200() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(ProjectionYearDto.simple(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0, null);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of()));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario_id").value(SCENARIO_ID.toString()))
                .andExpect(jsonPath("$.yearly_data[0].year").value(2026))
                .andExpect(jsonPath("$.final_balance").value(117700))
                .andExpect(jsonPath("$.unclassified_symbols").isArray())
                .andExpect(jsonPath("$.unclassified_symbols").isEmpty());
    }

    @Test
    void run_withUnclassifiedSymbols_includesFieldInResponse() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(ProjectionYearDto.simple(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0, null);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of("ZZZZ", "WEIRDX")));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario_id").value(SCENARIO_ID.toString()))
                .andExpect(jsonPath("$.unclassified_symbols").isArray())
                .andExpect(jsonPath("$.unclassified_symbols.length()").value(2))
                .andExpect(jsonPath("$.unclassified_symbols[0]").value("ZZZZ"))
                .andExpect(jsonPath("$.unclassified_symbols[1]").value("WEIRDX"));
    }

    @Test
    void run_noWarnings_omitsWarningsField() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(ProjectionYearDto.simple(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0, null);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of()));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings").doesNotExist());
    }

    @Test
    void run_unsupportedState_includesWarningsField() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(ProjectionYearDto.simple(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0, null);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of(),
                        List.of("State tax for NY is not modeled (treated as $0)")));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0]").value("State tax for NY is not modeled (treated as $0)"));
    }

    @Test
    void run_withTaxSpaceAndTerminalValue_serializesSnakeCaseFields() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(ProjectionYearDto.simple(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0, null);
        var bracket = new TaxSpaceYear.BracketRoom(
                new BigDecimal("0.12"), new BigDecimal("126700"), new BigDecimal("56700"));
        var taxSpaceYear = new TaxSpaceYear(2031, 62, new BigDecimal("70000"), new BigDecimal("0.12"),
                List.of(bracket), new BigDecimal("26700"), new BigDecimal("500000"),
                null, null, null, null, new BigDecimal("180000"),
                null, null, null, null, new BigDecimal("0.12"), BigDecimal.ZERO);
        var terminal = TerminalValue.compute(2060, new BigDecimal("400000"), new BigDecimal("300000"),
                new BigDecimal("200000"), new BigDecimal("0.24"), false);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of(), List.of(), List.of(taxSpaceYear), terminal));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tax_space.length()").value(1))
                .andExpect(jsonPath("$.tax_space[0].year").value(2031))
                .andExpect(jsonPath("$.tax_space[0].marginal_ordinary_rate").value(0.12))
                .andExpect(jsonPath("$.tax_space[0].bracket_room[0].gross_ceiling").value(126700))
                .andExpect(jsonPath("$.tax_space[0].ltcg_zero_room").value(26700))
                .andExpect(jsonPath("$.tax_space[0].effective_marginal_ordinary").value(0.12))
                .andExpect(jsonPath("$.terminal_value.heir_tax_rate").value(0.24))
                .andExpect(jsonPath("$.terminal_value.after_tax_legacy").value(804000))
                .andExpect(jsonPath("$.terminal_value.at_second_death").value(false));
    }

    @Test
    void run_emptyTaxSpaceAndNoTerminalValue_omitsTaxSpaceAndNullsTerminalValue() throws Exception {
        var result = new ProjectionResultResponse(
                SCENARIO_ID,
                List.of(ProjectionYearDto.simple(2026, 36,
                        new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("7700"), BigDecimal.ZERO,
                        new BigDecimal("117700"), false)),
                new BigDecimal("117700"), 0, null);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of()));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tax_space").doesNotExist())
                .andExpect(jsonPath("$.terminal_value").value(org.hamcrest.Matchers.nullValue()));
    }
}
