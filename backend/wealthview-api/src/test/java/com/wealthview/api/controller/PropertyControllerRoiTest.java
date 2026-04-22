package com.wealthview.api.controller;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.property.PropertyAnalyticsService;
import com.wealthview.core.property.PropertyRoiService;
import com.wealthview.core.property.PropertyService;
import com.wealthview.core.property.PropertyValuationService;
import com.wealthview.core.property.PropertyValuationSyncService;
import com.wealthview.core.property.dto.HoldScenarioResult;
import com.wealthview.core.property.dto.RoiAnalysisResponse;
import com.wealthview.core.property.dto.SellScenarioResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PropertyController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class PropertyControllerRoiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private PropertyService propertyService;
    @MockBean private PropertyValuationService valuationService;
    @MockBean private PropertyAnalyticsService analyticsService;
    @MockBean private PropertyRoiService roiService;
    @MockBean(name = "propertyValuationSyncService") private PropertyValuationSyncService syncService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private SessionStateValidator sessionStateValidator;

    private static final UUID PROPERTY_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();

    private RoiAnalysisResponse sampleResponse() {
        var hold = new HoldScenarioResult(
                new BigDecimal("450000.0000"),
                new BigDecimal("150000.0000"),
                new BigDecimal("72000.0000"),
                new BigDecimal("372000.0000")
        );
        var sell = new SellScenarioResult(
                new BigDecimal("350000.0000"),
                new BigDecimal("21000.0000"),
                new BigDecimal("5000.0000"),
                new BigDecimal("3000.0000"),
                new BigDecimal("321000.0000"),
                new BigDecimal("631352.4523")
        );
        return new RoiAnalysisResponse(
                "Rental Income",
                new BigDecimal("24000"),
                10,
                hold,
                sell,
                "sell",
                new BigDecimal("259352.4523")
        );
    }

    @Test
    void getRoiAnalysis_authenticated_returns200() throws Exception {
        when(roiService.computeRoiAnalysis(
                eq(TENANT_ID), eq(PROPERTY_ID), eq(SOURCE_ID),
                eq(10), eq(new BigDecimal("0.07")),
                eq(new BigDecimal("0.03")), eq(new BigDecimal("0.03"))))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/income-sources/{sourceId}/roi-analysis",
                        PROPERTY_ID, SOURCE_ID)
                        .with(authenticatedAdmin())
                        .param("years", "10")
                        .param("investment_return", "0.07")
                        .param("rent_growth", "0.03")
                        .param("expense_inflation", "0.03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income_source_name").value("Rental Income"))
                .andExpect(jsonPath("$.annual_rent").value(24000))
                .andExpect(jsonPath("$.comparison_years").value(10))
                .andExpect(jsonPath("$.hold.ending_property_value").exists())
                .andExpect(jsonPath("$.sell.net_proceeds").exists())
                .andExpect(jsonPath("$.advantage").value("sell"))
                .andExpect(jsonPath("$.advantage_amount").exists());
    }

    @Test
    void getRoiAnalysis_withDefaults_usesDefaultParams() throws Exception {
        when(roiService.computeRoiAnalysis(
                eq(TENANT_ID), eq(PROPERTY_ID), eq(SOURCE_ID),
                eq(10), eq(new BigDecimal("0.07")),
                eq(new BigDecimal("0.03")), eq(new BigDecimal("0.03"))))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/income-sources/{sourceId}/roi-analysis",
                        PROPERTY_ID, SOURCE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void getRoiAnalysis_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/properties/{propertyId}/income-sources/{sourceId}/roi-analysis",
                        PROPERTY_ID, SOURCE_ID))
                .andExpect(status().isUnauthorized());
    }
}
