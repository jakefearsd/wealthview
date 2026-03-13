package com.wealthview.api.controller;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.dashboard.CombinedPortfolioHistoryService;
import com.wealthview.core.dashboard.DashboardService;
import com.wealthview.core.dashboard.SnapshotProjectionService;
import com.wealthview.core.dashboard.dto.CombinedPortfolioDataPointDto;
import com.wealthview.core.dashboard.dto.CombinedPortfolioHistoryResponse;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse;
import com.wealthview.core.dashboard.dto.SnapshotProjectionDataPointDto;
import com.wealthview.core.dashboard.dto.SnapshotProjectionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;

    @MockBean
    private CombinedPortfolioHistoryService combinedPortfolioHistoryService;

    @MockBean
    private SnapshotProjectionService snapshotProjectionService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void getSummary_authenticated_returns200() throws Exception {
        var summary = new DashboardSummaryResponse(
                new BigDecimal("100000"),
                new BigDecimal("75000"),
                new BigDecimal("25000"),
                BigDecimal.ZERO,
                List.of(new DashboardSummaryResponse.AccountSummary("Brokerage", "brokerage",
                        new BigDecimal("75000"))),
                List.of(new DashboardSummaryResponse.AllocationEntry("Investments",
                        new BigDecimal("75000"), new BigDecimal("75")))
        );
        when(dashboardService.getSummary(TENANT_ID)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.net_worth").value(100000))
                .andExpect(jsonPath("$.accounts[0].name").value("Brokerage"));
    }

    @Test
    void getSummary_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPortfolioHistory_authenticated_returns200() throws Exception {
        var dataPoint = new CombinedPortfolioDataPointDto(
                LocalDate.of(2025, 1, 3),
                new BigDecimal("150000"),
                new BigDecimal("100000"),
                new BigDecimal("50000"));
        var response = new CombinedPortfolioHistoryResponse(List.of(dataPoint), 1, 2, 1);
        when(combinedPortfolioHistoryService.computeHistory(TENANT_ID, 2)).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/portfolio-history")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks").value(1))
                .andExpect(jsonPath("$.investment_account_count").value(2))
                .andExpect(jsonPath("$.property_count").value(1))
                .andExpect(jsonPath("$.data_points[0].total_value").value(150000))
                .andExpect(jsonPath("$.data_points[0].investment_value").value(100000))
                .andExpect(jsonPath("$.data_points[0].property_equity").value(50000));
    }

    @Test
    void getPortfolioHistory_withYearsParam_passesToService() throws Exception {
        var response = new CombinedPortfolioHistoryResponse(List.of(), 0, 0, 0);
        when(combinedPortfolioHistoryService.computeHistory(TENANT_ID, 5)).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/portfolio-history")
                        .param("years", "5")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks").value(0));
    }

    @Test
    void getPortfolioHistory_defaultYears_uses2() throws Exception {
        var response = new CombinedPortfolioHistoryResponse(List.of(), 0, 0, 0);
        when(combinedPortfolioHistoryService.computeHistory(TENANT_ID, 2)).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/portfolio-history")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void getSnapshotProjection_authenticated_returns200() throws Exception {
        var dataPoint = new SnapshotProjectionDataPointDto(
                0, LocalDate.of(2026, 3, 13),
                new BigDecimal("500000"), new BigDecimal("200000"), new BigDecimal("300000"));
        var response = new SnapshotProjectionResponse(
                List.of(dataPoint), 10, 2, 1, new BigDecimal("0.08"));
        when(snapshotProjectionService.computeProjection(TENANT_ID, 10, 10)).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/snapshot-projection")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projection_years").value(10))
                .andExpect(jsonPath("$.investment_account_count").value(2))
                .andExpect(jsonPath("$.property_count").value(1))
                .andExpect(jsonPath("$.portfolio_cagr").value(0.08))
                .andExpect(jsonPath("$.data_points[0].year").value(0))
                .andExpect(jsonPath("$.data_points[0].total_value").value(500000))
                .andExpect(jsonPath("$.data_points[0].investment_value").value(200000))
                .andExpect(jsonPath("$.data_points[0].property_equity").value(300000));
    }

    @Test
    void getSnapshotProjection_withParams_passesToService() throws Exception {
        var response = new SnapshotProjectionResponse(List.of(), 15, 0, 0, BigDecimal.ZERO);
        when(snapshotProjectionService.computeProjection(TENANT_ID, 15, 5)).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/snapshot-projection")
                        .param("years", "15")
                        .param("lookback", "5")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projection_years").value(15));
    }

    @Test
    void getSnapshotProjection_defaultParams_uses10and10() throws Exception {
        var response = new SnapshotProjectionResponse(List.of(), 10, 0, 0, BigDecimal.ZERO);
        when(snapshotProjectionService.computeProjection(TENANT_ID, 10, 10)).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/snapshot-projection")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void getSnapshotProjection_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/snapshot-projection"))
                .andExpect(status().isUnauthorized());
    }
}
