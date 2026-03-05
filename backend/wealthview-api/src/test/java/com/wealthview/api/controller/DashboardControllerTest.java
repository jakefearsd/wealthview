package com.wealthview.api.controller;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.dashboard.DashboardService;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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
    void getSummary_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isForbidden());
    }
}
