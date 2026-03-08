package com.wealthview.api.controller;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.property.PropertyAnalyticsService;
import com.wealthview.core.property.PropertyService;
import com.wealthview.core.property.PropertyValuationService;
import com.wealthview.core.property.PropertyValuationSyncService;
import com.wealthview.core.property.dto.EquityGrowthPoint;
import com.wealthview.core.property.dto.MortgageProgress;
import com.wealthview.core.property.dto.PropertyAnalyticsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PropertyController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class PropertyControllerAnalyticsTest {

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void getAnalytics_authenticated_returns200() throws Exception {
        var response = new PropertyAnalyticsResponse(
                "primary_residence",
                new BigDecimal("50000"),
                new BigDecimal("16.6667"),
                new MortgageProgress(
                        new BigDecimal("240000"),
                        new BigDecimal("230000"),
                        new BigDecimal("10000"),
                        new BigDecimal("4.1667"),
                        LocalDate.of(2050, 1, 1),
                        288
                ),
                List.of(new EquityGrowthPoint("2024-01", new BigDecimal("100000"),
                        new BigDecimal("350000"), new BigDecimal("250000"))),
                null, null, null, null, null
        );

        when(analyticsService.getAnalytics(eq(TENANT_ID), eq(PROPERTY_ID), isNull()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/properties/{id}/analytics", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.property_type").value("primary_residence"))
                .andExpect(jsonPath("$.total_appreciation").value(50000))
                .andExpect(jsonPath("$.mortgage_progress.original_loan_amount").value(240000))
                .andExpect(jsonPath("$.equity_growth[0].month").value("2024-01"))
                .andExpect(jsonPath("$.cap_rate").doesNotExist());
    }

    @Test
    void getAnalytics_withYearParam_passesYearToService() throws Exception {
        var response = new PropertyAnalyticsResponse(
                "investment", BigDecimal.ZERO, BigDecimal.ZERO,
                null, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("70000")
        );

        when(analyticsService.getAnalytics(eq(TENANT_ID), eq(PROPERTY_ID), eq(2024)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/properties/{id}/analytics", PROPERTY_ID)
                        .param("year", "2024")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());

        verify(analyticsService).getAnalytics(TENANT_ID, PROPERTY_ID, 2024);
    }

    @Test
    void getAnalytics_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/properties/{id}/analytics", PROPERTY_ID))
                .andExpect(status().isUnauthorized());
    }
}
