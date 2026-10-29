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
import com.wealthview.core.projection.BondTentAnalysisService;
import com.wealthview.core.projection.ProjectionService;
import com.wealthview.core.projection.ScenarioCrudService;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentCoveredYear;
import com.wealthview.core.projection.dto.BondTentMixSizing;
import com.wealthview.core.projection.dto.BondTentReplay;
import com.wealthview.core.projection.dto.BondTentReplayOutcome;
import com.wealthview.core.projection.dto.BondTentReplayYear;
import com.wealthview.core.projection.dto.BondTentResult;
import com.wealthview.core.projection.dto.BondTentSizing;
import com.wealthview.core.projection.dto.BondTentSimulation;
import com.wealthview.core.projection.dto.BondTentSimulationColumn;
import com.wealthview.core.projection.dto.BondTentSizingReason;
import com.wealthview.core.projection.dto.BondTentYear;
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
import static org.mockito.Mockito.verifyNoInteractions;
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

    @MockitoBean
    private BondTentAnalysisService bondTentAnalysisService;

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

    @Test
    void serialize_noTent_omitsBondTentKey() throws Exception {
        var result = new ProjectionResultResponse(SCENARIO_ID, List.of(), BigDecimal.ZERO, 0, null);
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of()));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bond_tent").doesNotExist());
    }

    @Test
    void serialize_withTent_includesSnakeCaseBondTent() throws Exception {
        var result = new ProjectionResultResponse(SCENARIO_ID, List.of(), BigDecimal.ZERO, 0, null);
        var balances = new BondMaturityValues(new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
        var sizing = new BondTentSizing(new BigDecimal("1000000.00"), new BigDecimal("5"),
                List.of(new BondTentMixSizing("Plan", new BigDecimal("0.020000"), new BigDecimal("1.00"),
                        new BigDecimal("0.000001"), new BigDecimal("2.00"), BondTentSizingReason.INCOME,
                        new BigDecimal("-1.00"))),
                List.of(new BondTentCoveredYear(2030, new BigDecimal("50000.00"), new BigDecimal("1.000000"))));
        var tent = new BondTentResult(sizing,
                List.of(new BondTentYear(2030, balances, new BigDecimal("1.00"), new BigDecimal("1.00"))));
        when(projectionService.runProjection(TENANT_ID, SCENARIO_ID))
                .thenReturn(new ProjectionRunResult(result, List.of(), List.of(), List.of(), null, tent));

        mockMvc.perform(get("/api/v1/projections/{id}/run", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bond_tent.sizing.mixes[0].real_yield").value(0.02))
                .andExpect(jsonPath("$.bond_tent.sizing.mixes[0].reason").value("INCOME"))
                .andExpect(jsonPath("$.bond_tent.sizing.covered_years[0].year").value(2030))
                .andExpect(jsonPath("$.bond_tent.years[0].balances.ultrashort").value(1.0))
                .andExpect(jsonPath("$.bond_tent.years[0].build_up_target").value(1.0));
    }

    private static final String TENT_BODY = """
            {"coverage_years":5,"build_up_start_years":10,
             "mixes":[{"name":"Plan","shares":{"ultrashort":0.25,"short":0.25,"intermediate":0.25,"long":0.25}}],
             "yields":{"ultrashort":0.03,"short":0.035,"intermediate":0.04,"long":0.045}}
            """;

    @Test
    void sizeBondTent_valid_returns200WithSnakeCaseSizing() throws Exception {
        var sizing = new BondTentSizing(new BigDecimal("1000000.00"), new BigDecimal("5"),
                List.of(new BondTentMixSizing("Plan", new BigDecimal("0.020000"), new BigDecimal("150000.00"),
                        new BigDecimal("0.150000"), new BigDecimal("200000.00"), BondTentSizingReason.INCOME,
                        new BigDecimal("-1.00"))),
                List.of(new BondTentCoveredYear(2030, new BigDecimal("50000.00"), new BigDecimal("1.000000"))));
        when(projectionService.sizeBondTent(eq(TENANT_ID), eq(SCENARIO_ID), any(BondTentConfig.class)))
                .thenReturn(sizing);

        mockMvc.perform(post("/api/v1/projections/{id}/bond-tent/sizing", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mixes[0].percent_of_portfolio").value(0.15))
                .andExpect(jsonPath("$.covered_years[0].year").value(2030));
    }

    @Test
    void sizeBondTent_invalidShares_returns400() throws Exception {
        when(projectionService.sizeBondTent(eq(TENANT_ID), eq(SCENARIO_ID), any(BondTentConfig.class)))
                .thenThrow(new IllegalArgumentException("Mix shares must sum to 1"));

        mockMvc.perform(post("/api/v1/projections/{id}/bond-tent/sizing", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TENT_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sizeBondTent_malformedBody_returns400WithErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/projections/{id}/bond-tent/sizing", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coverage_years\": \"lots\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(projectionService);
    }

    @Test
    void sizeBondTent_missingBody_returns400WithErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/projections/{id}/bond-tent/sizing", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verifyNoInteractions(projectionService);
    }

    @Test
    void sizeBondTent_unknownScenario_returns404() throws Exception {
        when(projectionService.sizeBondTent(eq(TENANT_ID), eq(SCENARIO_ID), any(BondTentConfig.class)))
                .thenThrow(new EntityNotFoundException("Scenario not found"));

        mockMvc.perform(post("/api/v1/projections/{id}/bond-tent/sizing", SCENARIO_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TENT_BODY))
                .andExpect(status().isNotFound());
    }

    private static BondTentReplay sampleReplay() {
        var year = new BondTentReplayYear(2030, 62, new BigDecimal("900000.00"), new BigDecimal("200000.00"),
                BigDecimal.ZERO, new BigDecimal("12000.00"), true, 2008);
        var outcome = new BondTentReplayOutcome("No tent", null, new BigDecimal("500000.00"),
                new BigDecimal("12000.00"), null, null, null, List.of(year));
        return new BondTentReplay("2007–09", 2008, 2009, 2030, 0, 2030, new BigDecimal("5"), 70,
                List.of(0, 1, 2, 3), 1954, 2025, List.of(outcome));
    }

    @Test
    void bondTentSimulation_returns200WithSnakeCaseColumns() throws Exception {
        var column = new BondTentSimulationColumn("No tent", null, new BigDecimal("0.8700"), null, null,
                new BigDecimal("1000000.00"));
        when(bondTentAnalysisService.simulate(TENANT_ID, SCENARIO_ID))
                .thenReturn(new BondTentSimulation(5000, new BigDecimal("5"), List.of(column)));

        mockMvc.perform(get("/api/v1/projections/{id}/bond-tent/simulation", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planned_years").value(5))
                .andExpect(jsonPath("$.columns[0].success_rate").value(0.87));
    }

    @Test
    void bondTentSimulation_unknownScenario_returns404() throws Exception {
        when(bondTentAnalysisService.simulate(TENANT_ID, SCENARIO_ID))
                .thenThrow(new EntityNotFoundException("Scenario not found"));

        mockMvc.perform(get("/api/v1/projections/{id}/bond-tent/simulation", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void bondTentReplay_crash_returns200() throws Exception {
        when(bondTentAnalysisService.replay(TENANT_ID, SCENARIO_ID, "financial_crisis_2008", null, 0))
                .thenReturn(sampleReplay());

        mockMvc.perform(get("/api/v1/projections/{id}/bond-tent/replay", SCENARIO_ID)
                        .param("crash", "financial_crisis_2008")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].years[0].stocks_sold_while_down").value(12000.0))
                .andExpect(jsonPath("$.available_offsets[1]").value(1))
                .andExpect(jsonPath("$.crash_label").value("2007–09"));
    }

    @Test
    void bondTentReplay_startYearParam_isPassedThrough() throws Exception {
        when(bondTentAnalysisService.replay(TENANT_ID, SCENARIO_ID, null, 1990, 1)).thenReturn(sampleReplay());

        mockMvc.perform(get("/api/v1/projections/{id}/bond-tent/replay", SCENARIO_ID)
                        .param("start_year", "1990")
                        .param("offset", "1")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void bondTentReplay_invalid_returns400() throws Exception {
        when(bondTentAnalysisService.replay(TENANT_ID, SCENARIO_ID, null, null, 0))
                .thenThrow(new IllegalArgumentException("Choose a crash or a start year"));

        mockMvc.perform(get("/api/v1/projections/{id}/bond-tent/replay", SCENARIO_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isBadRequest());
    }

    private void assertScenarioRejected(String extraFields) throws Exception {
        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Plan", "retirement_date": "2055-01-01", "end_age": 90,
                                 "inflation_rate": 0.03, "birth_year": 1990, %s,
                                 "accounts": [{"initial_balance": 100000, "annual_contribution": 10000,
                                               "expected_return": 0.07}]}
                                """.formatted(extraFields)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void create_inflationRateAboveBound_returns400() throws Exception {
        assertScenarioRejected("\"inflation_rate\": 5");
    }

    @Test
    void create_inflationRateBelowBound_returns400() throws Exception {
        assertScenarioRejected("\"inflation_rate\": -0.5");
    }

    @Test
    void create_unknownFilingStatus_returns400() throws Exception {
        assertScenarioRejected("\"filing_status\": \"martian\"");
    }

    @Test
    void create_unknownWithdrawalStrategy_returns400() throws Exception {
        assertScenarioRejected("\"withdrawal_strategy\": \"yolo\"");
    }

    @Test
    void create_unknownWithdrawalOrder_returns400() throws Exception {
        assertScenarioRejected("\"withdrawal_order\": \"random\"");
    }

    @Test
    void create_unknownRothConversionStrategy_returns400() throws Exception {
        assertScenarioRejected("\"roth_conversion_strategy\": \"wizard\"");
    }

    @Test
    void create_blankName_returns400() throws Exception {
        assertScenarioRejected("\"name\": \"\"");
    }

    @Test
    void create_negativeInitialBalance_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Plan", "retirement_date": "2055-01-01", "end_age": 90,
                                 "inflation_rate": 0.03, "birth_year": 1990,
                                 "accounts": [{"initial_balance": -50000, "annual_contribution": 10000,
                                               "expected_return": 0.07}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_knownEnumValues_returns201() throws Exception {
        when(scenarioCrudService.createScenario(eq(TENANT_ID), any()))
                .thenReturn(sampleScenario());

        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Plan", "retirement_date": "2055-01-01", "end_age": 90,
                                 "inflation_rate": 0.03, "birth_year": 1990,
                                 "filing_status": "married_filing_jointly",
                                 "withdrawal_strategy": "vanguard_dynamic_spending",
                                 "withdrawal_order": "dynamic_sequencing",
                                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22,
                                 "accounts": []}
                                """))
                .andExpect(status().isCreated());
    }

    private void assertScenarioAccepted(String extraFields, String accountsJson) throws Exception {
        when(scenarioCrudService.createScenario(eq(TENANT_ID), any()))
                .thenReturn(sampleScenario());

        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Plan", "retirement_date": "2055-01-01", "end_age": 90,
                                 "inflation_rate": 0.03, "birth_year": 1990%s,
                                 "accounts": %s}
                                """.formatted(extraFields, accountsJson)))
                .andExpect(status().isCreated());
    }

    private void assertScenarioAccepted(String extraFields) throws Exception {
        assertScenarioAccepted(extraFields, "[]");
    }

    @Test
    void create_linkedAccountWithNegativeLiveBalanceAndCostBasis_returns201() throws Exception {
        // A linked bank account echoes its live balance, which is negative after an import with
        // no opening balance. The backend ignores both values for linked rows.
        assertScenarioAccepted("", """
                [{"linked_account_id": "%s", "initial_balance": -2000, "cost_basis": -2000,
                  "annual_contribution": 0, "account_type": "taxable"}]""".formatted(UUID.randomUUID()));
    }

    @Test
    void create_unlinkedNegativeCostBasis_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/projections")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Plan", "retirement_date": "2055-01-01", "end_age": 90,
                                 "inflation_rate": 0.03, "birth_year": 1990,
                                 "accounts": [{"initial_balance": 1000, "cost_basis": -1,
                                               "annual_contribution": 0, "expected_return": 0.07}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_negativeAnnualContribution_returns201() throws Exception {
        // A negative contribution models a planned pre-retirement withdrawal; legacy rows hold it.
        assertScenarioAccepted("", """
                [{"initial_balance": 100000, "annual_contribution": -10000, "expected_return": 0.07,
                  "account_type": "taxable"}]""");
    }

    @Test
    void create_legacyCommaListWithdrawalOrder_returns201() throws Exception {
        assertScenarioAccepted(", \"withdrawal_order\": \"roth,taxable,traditional\"");
    }

    @Test
    void create_legacyCommaListWithdrawalOrderWithSpaces_returns201() throws Exception {
        assertScenarioAccepted(", \"withdrawal_order\": \"Traditional, Roth, Taxable\"");
    }

    @Test
    void create_upperCaseWithdrawalOrder_returns201() throws Exception {
        assertScenarioAccepted(", \"withdrawal_order\": \"TRADITIONAL_FIRST\"");
    }

    @Test
    void create_upperCaseFilingStatus_returns201() throws Exception {
        assertScenarioAccepted(", \"filing_status\": \"MARRIED_FILING_JOINTLY\"");
    }

    @Test
    void create_whitespaceOnlyEnumFields_returns201() throws Exception {
        // Every parser treats a blank value as "not set" and falls back to its default.
        assertScenarioAccepted(", \"filing_status\": \" \", \"withdrawal_strategy\": \" \","
                + " \"withdrawal_order\": \" \", \"roth_conversion_strategy\": \" \"");
    }

    @Test
    void create_upperCaseWithdrawalStrategy_returns400() throws Exception {
        // WithdrawalStrategyFactory matches case-sensitively, so an upper-case token was never honoured.
        assertScenarioRejected("\"withdrawal_strategy\": \"DYNAMIC_PERCENTAGE\"");
    }

    @Test
    void create_upperCaseRothConversionStrategy_returns400() throws Exception {
        assertScenarioRejected("\"roth_conversion_strategy\": \"FILL_BRACKET\"");
    }

    @Test
    void create_commaListWithUnknownPool_returns400() throws Exception {
        assertScenarioRejected("\"withdrawal_order\": \"roth,brokerage\"");
    }
}
