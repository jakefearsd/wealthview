package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.income.IncomeSourceService;
import com.wealthview.core.income.dto.CreateIncomeSourceRequest;
import com.wealthview.core.income.dto.IncomeSourceResponse;
import com.wealthview.core.income.dto.UpdateIncomeSourceRequest;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IncomeSourceController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class IncomeSourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IncomeSourceService incomeSourceService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private SessionStateValidator sessionStateValidator;

    private static final UUID SOURCE_ID = UUID.randomUUID();

    private IncomeSourceResponse sampleResponse() {
        return new IncomeSourceResponse(
                SOURCE_ID, "Social Security", "social_security",
                new BigDecimal("30000"), 67, null,
                new BigDecimal("0.02"), false, "partially_taxable",
                null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void create_validInput_returns201() throws Exception {
        when(incomeSourceService.create(eq(TENANT_ID), any(CreateIncomeSourceRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/income-sources")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Social Security", "income_type": "social_security",
                                 "annual_amount": 30000, "start_age": 67,
                                 "inflation_rate": 0.02, "tax_treatment": "partially_taxable"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Social Security"))
                .andExpect(jsonPath("$.income_type").value("social_security"))
                .andExpect(jsonPath("$.annual_amount").value(30000));
    }

    @Test
    void list_returns200() throws Exception {
        when(incomeSourceService.list(TENANT_ID)).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/income-sources")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Social Security"));
    }

    @Test
    void get_existing_returns200() throws Exception {
        when(incomeSourceService.get(TENANT_ID, SOURCE_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/income-sources/{id}", SOURCE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Social Security"))
                .andExpect(jsonPath("$.tax_treatment").value("partially_taxable"));
    }

    @Test
    void get_nonexistent_returns404() throws Exception {
        when(incomeSourceService.get(TENANT_ID, SOURCE_ID))
                .thenThrow(new EntityNotFoundException("Income source not found"));

        mockMvc.perform(get("/api/v1/income-sources/{id}", SOURCE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_validInput_returns200() throws Exception {
        var updated = new IncomeSourceResponse(
                SOURCE_ID, "Updated SS", "social_security",
                new BigDecimal("35000"), 68, null,
                new BigDecimal("0.025"), false, "partially_taxable",
                null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(incomeSourceService.update(eq(TENANT_ID), eq(SOURCE_ID), any(UpdateIncomeSourceRequest.class)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/v1/income-sources/{id}", SOURCE_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Updated SS", "income_type": "social_security",
                                 "annual_amount": 35000, "start_age": 68,
                                 "inflation_rate": 0.025, "tax_treatment": "partially_taxable"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated SS"))
                .andExpect(jsonPath("$.annual_amount").value(35000));
    }

    @Test
    void delete_existing_returns204() throws Exception {
        doNothing().when(incomeSourceService).delete(TENANT_ID, SOURCE_ID);

        mockMvc.perform(delete("/api/v1/income-sources/{id}", SOURCE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_nonexistent_returns404() throws Exception {
        doThrow(new EntityNotFoundException("Income source not found"))
                .when(incomeSourceService).delete(TENANT_ID, SOURCE_ID);

        mockMvc.perform(delete("/api/v1/income-sources/{id}", SOURCE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_withRentalProperty_returns201() throws Exception {
        var propertyId = UUID.randomUUID();
        var response = new IncomeSourceResponse(
                SOURCE_ID, "Rental Income", "rental_property",
                new BigDecimal("24000"), 60, null,
                BigDecimal.ZERO, false, "rental_passive",
                propertyId, "123 Elm St",
                OffsetDateTime.now(), OffsetDateTime.now());
        when(incomeSourceService.create(eq(TENANT_ID), any(CreateIncomeSourceRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/income-sources")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Rental Income", "income_type": "rental_property",
                                 "annual_amount": 24000, "start_age": 60,
                                 "tax_treatment": "rental_passive",
                                 "property_id": "%s"}
                                """.formatted(propertyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.property_id").value(propertyId.toString()))
                .andExpect(jsonPath("$.property_address").value("123 Elm St"));
    }
}
