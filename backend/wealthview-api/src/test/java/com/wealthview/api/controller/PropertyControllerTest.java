package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.PropertyAnalyticsService;
import com.wealthview.core.property.PropertyService;
import com.wealthview.core.property.PropertyValuationService;
import com.wealthview.core.property.PropertyValuationSyncService;
import com.wealthview.core.property.dto.MonthlyCashFlowEntry;
import com.wealthview.core.property.dto.PropertyExpenseRequest;
import com.wealthview.core.property.dto.PropertyIncomeRequest;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.property.dto.PropertyValuationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PropertyController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class PropertyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PropertyService propertyService;

    @MockBean
    private PropertyValuationService valuationService;

    @MockBean
    private PropertyAnalyticsService analyticsService;

    @MockBean(name = "propertyValuationSyncService")
    private PropertyValuationSyncService syncService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID PROPERTY_ID = UUID.randomUUID();

    private PropertyResponse sampleResponse() {
        return new PropertyResponse(PROPERTY_ID, "123 Main St",
                new BigDecimal("300000"), LocalDate.of(2020, 6, 1),
                new BigDecimal("350000"), new BigDecimal("200000"),
                new BigDecimal("150000"),
                null, null, null, null, false, false, "primary_residence");
    }

    @Test
    void create_validInput_returns201() throws Exception {
        when(propertyService.create(eq(TENANT_ID), any(PropertyRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/properties")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"address": "123 Main St", "purchase_price": 300000,
                                 "purchase_date": "2020-06-01", "current_value": 350000,
                                 "mortgage_balance": 200000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.address").value("123 Main St"));
    }

    @Test
    void list_returns200() throws Exception {
        when(propertyService.list(TENANT_ID)).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/properties")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].address").value("123 Main St"));
    }

    @Test
    void get_existing_returns200() throws Exception {
        when(propertyService.get(TENANT_ID, PROPERTY_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/properties/{id}", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("123 Main St"));
    }

    @Test
    void get_nonExistent_returns404() throws Exception {
        when(propertyService.get(TENANT_ID, PROPERTY_ID))
                .thenThrow(new EntityNotFoundException("Property not found"));

        mockMvc.perform(get("/api/v1/properties/{id}", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_validInput_returns200() throws Exception {
        when(propertyService.update(eq(TENANT_ID), eq(PROPERTY_ID), any(PropertyRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(put("/api/v1/properties/{id}", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"address": "123 Main St", "purchase_price": 300000,
                                 "purchase_date": "2020-06-01", "current_value": 375000,
                                 "mortgage_balance": 195000}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void update_notFound_returns404() throws Exception {
        when(propertyService.update(eq(TENANT_ID), eq(PROPERTY_ID), any(PropertyRequest.class)))
                .thenThrow(new EntityNotFoundException("Property not found"));

        mockMvc.perform(put("/api/v1/properties/{id}", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"address": "123 Main St", "purchase_price": 300000,
                                 "purchase_date": "2020-06-01", "current_value": 375000,
                                 "mortgage_balance": 195000}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void delete_existing_returns204() throws Exception {
        doNothing().when(propertyService).delete(TENANT_ID, PROPERTY_ID);

        mockMvc.perform(delete("/api/v1/properties/{id}", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void addIncome_validInput_returns201() throws Exception {
        doNothing().when(propertyService).addIncome(eq(TENANT_ID), eq(PROPERTY_ID),
                any(PropertyIncomeRequest.class));

        mockMvc.perform(post("/api/v1/properties/{id}/income", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2025-01-01", "amount": 2500, "category": "rent",
                                 "description": "January rent"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void addExpense_validInput_returns201() throws Exception {
        doNothing().when(propertyService).addExpense(eq(TENANT_ID), eq(PROPERTY_ID),
                any(PropertyExpenseRequest.class));

        mockMvc.perform(post("/api/v1/properties/{id}/expenses", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2025-01-01", "amount": 1800, "category": "mortgage",
                                 "description": "January mortgage"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void addIncome_withAnnualFrequency_returns201() throws Exception {
        doNothing().when(propertyService).addIncome(eq(TENANT_ID), eq(PROPERTY_ID),
                any(PropertyIncomeRequest.class));

        mockMvc.perform(post("/api/v1/properties/{id}/income", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2025-01-01", "amount": 12000, "category": "rent",
                                 "frequency": "annual"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void addExpense_withAnnualFrequency_returns201() throws Exception {
        doNothing().when(propertyService).addExpense(eq(TENANT_ID), eq(PROPERTY_ID),
                any(PropertyExpenseRequest.class));

        mockMvc.perform(post("/api/v1/properties/{id}/expenses", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2025-01-01", "amount": 6000, "category": "tax",
                                 "frequency": "annual"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void getCashFlow_returns200() throws Exception {
        var entry = new MonthlyCashFlowEntry("2025-01",
                new BigDecimal("2500"), new BigDecimal("1800"), new BigDecimal("700"));
        when(propertyService.getMonthlyCashFlow(eq(TENANT_ID), eq(PROPERTY_ID),
                any(YearMonth.class), any(YearMonth.class)))
                .thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/properties/{id}/cashflow", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .param("from", "2025-01")
                        .param("to", "2025-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].total_income").value(2500));
    }

    @Test
    void getValuations_returns200() throws Exception {
        var valuation = new PropertyValuationResponse(UUID.randomUUID(),
                LocalDate.of(2025, 3, 1), new BigDecimal("400000"), "zillow");
        when(valuationService.getHistory(TENANT_ID, PROPERTY_ID))
                .thenReturn(List.of(valuation));

        mockMvc.perform(get("/api/v1/properties/{id}/valuations", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("zillow"))
                .andExpect(jsonPath("$[0].value").value(400000));
    }

    @Test
    void refreshValuation_returns202() throws Exception {
        doNothing().when(syncService).syncSingleProperty(TENANT_ID, PROPERTY_ID);

        mockMvc.perform(post("/api/v1/properties/{id}/valuations/refresh", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isAccepted());
    }
}
