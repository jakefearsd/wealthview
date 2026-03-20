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
import com.wealthview.core.property.dto.CostSegAllocation;
import com.wealthview.core.property.dto.DepreciationScheduleResult;
import com.wealthview.core.property.dto.MonthlyCashFlowDetailEntry;
import com.wealthview.core.property.dto.MonthlyCashFlowEntry;
import com.wealthview.core.property.dto.PropertyExpenseRequest;
import com.wealthview.core.property.dto.PropertyExpenseResponse;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.property.dto.PropertyValuationResponse;
import com.wealthview.core.property.dto.ValuationRefreshResponse;
import com.wealthview.core.property.dto.ZillowSearchResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                null, null, null, null, false, false, "primary_residence",
                null, null, null, null,
                null, null, "none", new BigDecimal("27.5"),
                List.of(), BigDecimal.ONE, null);
    }

    private PropertyResponse sampleResponseWithFinancialFields() {
        return new PropertyResponse(PROPERTY_ID, "123 Main St",
                new BigDecimal("300000"), LocalDate.of(2020, 6, 1),
                new BigDecimal("350000"), new BigDecimal("200000"),
                new BigDecimal("150000"),
                null, null, null, null, false, false, "primary_residence",
                new BigDecimal("0.03000"), new BigDecimal("4500.0000"), new BigDecimal("1800.0000"), null,
                null, null, "none", new BigDecimal("27.5"),
                List.of(), BigDecimal.ONE, null);
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
    void create_withFinancialFields_returnsFieldsInResponse() throws Exception {
        when(propertyService.create(eq(TENANT_ID), any(PropertyRequest.class)))
                .thenReturn(sampleResponseWithFinancialFields());

        mockMvc.perform(post("/api/v1/properties")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"address": "123 Main St", "purchase_price": 300000,
                                 "purchase_date": "2020-06-01", "current_value": 350000,
                                 "mortgage_balance": 200000,
                                 "annual_appreciation_rate": 0.03,
                                 "annual_property_tax": 4500,
                                 "annual_insurance_cost": 1800}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.annual_appreciation_rate").value(0.03000))
                .andExpect(jsonPath("$.annual_property_tax").value(4500.0000))
                .andExpect(jsonPath("$.annual_insurance_cost").value(1800.0000));
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
    void addIncome_validInput_returns201() throws Exception {
        doNothing().when(propertyService).addIncome(eq(TENANT_ID), eq(PROPERTY_ID),
                any(com.wealthview.core.property.dto.PropertyIncomeRequest.class));

        mockMvc.perform(post("/api/v1/properties/{id}/income", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2024-01-15", "amount": 2000, "category": "rent",
                                 "description": "January rent"}
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
    void getCashFlowDetail_returns200WithCategoryBreakdown() throws Exception {
        var categories = new LinkedHashMap<String, BigDecimal>();
        categories.put("mortgage", new BigDecimal("1200"));
        categories.put("insurance", new BigDecimal("150"));
        var entry = new MonthlyCashFlowDetailEntry("2025-01",
                new BigDecimal("2200"), categories,
                new BigDecimal("1350"), new BigDecimal("850"));
        when(propertyService.getMonthlyCashFlowDetail(eq(TENANT_ID), eq(PROPERTY_ID),
                any(YearMonth.class), any(YearMonth.class)))
                .thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/properties/{id}/cashflow-detail", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .param("from", "2025-01")
                        .param("to", "2025-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].total_income").value(2200))
                .andExpect(jsonPath("$[0].expenses_by_category.mortgage").value(1200))
                .andExpect(jsonPath("$[0].expenses_by_category.insurance").value(150))
                .andExpect(jsonPath("$[0].total_expenses").value(1350))
                .andExpect(jsonPath("$[0].net_cash_flow").value(850));
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
    void refreshValuation_updated_returns200() throws Exception {
        when(syncService.refreshProperty(TENANT_ID, PROPERTY_ID))
                .thenReturn(ValuationRefreshResponse.updated(new BigDecimal("400000")));

        mockMvc.perform(post("/api/v1/properties/{id}/valuations/refresh", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("updated"))
                .andExpect(jsonPath("$.value").value(400000));
    }

    @Test
    void refreshValuation_multipleMatches_returnsCandidates() throws Exception {
        var candidates = java.util.List.of(
                new ZillowSearchResult("111", "123 Main St Unit A", new BigDecimal("350000")),
                new ZillowSearchResult("222", "123 Main St Unit B", new BigDecimal("375000"))
        );
        when(syncService.refreshProperty(TENANT_ID, PROPERTY_ID))
                .thenReturn(ValuationRefreshResponse.multipleMatches(candidates));

        mockMvc.perform(post("/api/v1/properties/{id}/valuations/refresh", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("multiple_matches"))
                .andExpect(jsonPath("$.candidates").isArray())
                .andExpect(jsonPath("$.candidates.length()").value(2));
    }

    @Test
    void listExpenses_returns200WithExpenses() throws Exception {
        var expenses = List.of(
                new PropertyExpenseResponse(UUID.randomUUID(), LocalDate.of(2025, 3, 1),
                        new BigDecimal("500"), "maintenance", "Plumbing fix", "monthly"),
                new PropertyExpenseResponse(UUID.randomUUID(), LocalDate.of(2025, 1, 1),
                        new BigDecimal("6000"), "tax", null, "annual")
        );
        when(propertyService.listExpenses(TENANT_ID, PROPERTY_ID)).thenReturn(expenses);

        mockMvc.perform(get("/api/v1/properties/{id}/expenses", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].category").value("maintenance"))
                .andExpect(jsonPath("$[0].description").value("Plumbing fix"))
                .andExpect(jsonPath("$[0].amount").value(500))
                .andExpect(jsonPath("$[1].category").value("tax"))
                .andExpect(jsonPath("$[1].frequency").value("annual"));
    }

    @Test
    void deleteExpense_returns204() throws Exception {
        var expenseId = UUID.randomUUID();
        doNothing().when(propertyService).deleteExpense(TENANT_ID, PROPERTY_ID, expenseId);

        mockMvc.perform(delete("/api/v1/properties/{id}/expenses/{expenseId}", PROPERTY_ID, expenseId)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void selectZpid_returns200() throws Exception {
        when(syncService.selectZpid(TENANT_ID, PROPERTY_ID, "12345"))
                .thenReturn(ValuationRefreshResponse.updated(new BigDecimal("400000")));

        mockMvc.perform(post("/api/v1/properties/{id}/valuations/select-zpid", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zpid": "12345"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("updated"));
    }

    @Test
    void getDepreciationSchedule_happyPath_returns200() throws Exception {
        var entries = List.of(
                new DepreciationScheduleResult.YearEntry(2024, new BigDecimal("10909.09"),
                        new BigDecimal("10909.09"), new BigDecimal("289090.91")),
                new DepreciationScheduleResult.YearEntry(2025, new BigDecimal("10909.09"),
                        new BigDecimal("21818.18"), new BigDecimal("278181.82"))
        );
        var result = new DepreciationScheduleResult(
                "straight_line", new BigDecimal("300000"),
                new BigDecimal("27.5"), LocalDate.of(2024, 1, 15), entries);

        when(propertyService.getDepreciationSchedule(TENANT_ID, PROPERTY_ID)).thenReturn(result);

        mockMvc.perform(get("/api/v1/properties/{id}/depreciation-schedule", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depreciation_method").value("straight_line"))
                .andExpect(jsonPath("$.depreciable_basis").value(300000))
                .andExpect(jsonPath("$.schedule").isArray())
                .andExpect(jsonPath("$.schedule.length()").value(2))
                .andExpect(jsonPath("$.schedule[0].tax_year").value(2024))
                .andExpect(jsonPath("$.schedule[0].annual_depreciation").value(10909.09));
    }

    @Test
    void getDepreciationSchedule_methodNone_returns400() throws Exception {
        when(propertyService.getDepreciationSchedule(TENANT_ID, PROPERTY_ID))
                .thenThrow(new IllegalArgumentException("Depreciation is not configured for this property"));

        mockMvc.perform(get("/api/v1/properties/{id}/depreciation-schedule", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDepreciationSchedule_notFound_returns404() throws Exception {
        when(propertyService.getDepreciationSchedule(TENANT_ID, PROPERTY_ID))
                .thenThrow(new EntityNotFoundException("Property not found: " + PROPERTY_ID));

        mockMvc.perform(get("/api/v1/properties/{id}/depreciation-schedule", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void create_withCostSegAllocations_returns201() throws Exception {
        var response = new PropertyResponse(PROPERTY_ID, "123 Main St",
                new BigDecimal("300000"), LocalDate.of(2020, 6, 1),
                new BigDecimal("350000"), new BigDecimal("200000"),
                new BigDecimal("150000"),
                null, null, null, null, false, false, "investment",
                null, null, null, null,
                LocalDate.of(2020, 6, 1), new BigDecimal("50000"), "cost_segregation", new BigDecimal("27.5"),
                List.of(new CostSegAllocation("5yr", new BigDecimal("15000")),
                        new CostSegAllocation("27_5yr", new BigDecimal("235000"))),
                BigDecimal.ONE, null);

        when(propertyService.create(eq(TENANT_ID), any(PropertyRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/properties")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"address": "123 Main St", "purchase_price": 300000,
                                 "purchase_date": "2020-06-01", "current_value": 350000,
                                 "mortgage_balance": 200000, "property_type": "investment",
                                 "depreciation_method": "cost_segregation",
                                 "in_service_date": "2020-06-01", "land_value": 50000,
                                 "cost_seg_allocations": [
                                   {"asset_class": "5yr", "allocation": 15000},
                                   {"asset_class": "27_5yr", "allocation": 235000}
                                 ],
                                 "bonus_depreciation_rate": 1.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.depreciation_method").value("cost_segregation"))
                .andExpect(jsonPath("$.cost_seg_allocations").isArray())
                .andExpect(jsonPath("$.cost_seg_allocations.length()").value(2))
                .andExpect(jsonPath("$.bonus_depreciation_rate").value(1));
    }

    @Test
    void getDepreciationSchedule_costSeg_returnsEnrichedResponse() throws Exception {
        var allocations = List.of(
                new CostSegAllocation("5yr", new BigDecimal("15000")),
                new CostSegAllocation("27_5yr", new BigDecimal("235000")));
        var breakdowns = List.of(
                new DepreciationScheduleResult.ClassBreakdown("5yr", new BigDecimal("5"),
                        new BigDecimal("15000"), new BigDecimal("15000"), BigDecimal.ZERO, 0),
                new DepreciationScheduleResult.ClassBreakdown("27_5yr", new BigDecimal("27.5"),
                        new BigDecimal("235000"), BigDecimal.ZERO, new BigDecimal("8545.4545"), 29));
        var entries = List.of(
                new DepreciationScheduleResult.YearEntry(2024, new BigDecimal("23000"),
                        new BigDecimal("23000"), new BigDecimal("227000")));
        var result = new DepreciationScheduleResult(
                "cost_segregation", new BigDecimal("250000"),
                new BigDecimal("27.5"), LocalDate.of(2024, 1, 15), entries,
                BigDecimal.ONE, allocations, breakdowns);

        when(propertyService.getDepreciationSchedule(TENANT_ID, PROPERTY_ID)).thenReturn(result);

        mockMvc.perform(get("/api/v1/properties/{id}/depreciation-schedule", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depreciation_method").value("cost_segregation"))
                .andExpect(jsonPath("$.bonus_depreciation_rate").value(1))
                .andExpect(jsonPath("$.cost_seg_allocations").isArray())
                .andExpect(jsonPath("$.class_breakdowns").isArray())
                .andExpect(jsonPath("$.class_breakdowns.length()").value(2))
                .andExpect(jsonPath("$.class_breakdowns[0].asset_class").value("5yr"))
                .andExpect(jsonPath("$.class_breakdowns[0].bonus_amount").value(15000));
    }
}
