package com.wealthview.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.LoginActivityService;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.config.SystemStatsService;
import com.wealthview.core.price.PriceService;
import com.wealthview.core.tenant.TenantService;
import com.wealthview.core.tenant.UserManagementService;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the SuperAdminController.triggerPriceSync() 503 branch: when
 * PriceSyncService is not available (Finnhub not configured), the endpoint
 * returns 503 with a descriptive error body.
 *
 * <p>Uses a separate test class without @MockBean for PriceSyncService
 * so that it is null (injected as @Nullable), triggering the 503 branch.
 */
@WebMvcTest(SuperAdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class SuperAdminControllerNoPriceSyncTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantService tenantService;

    // PriceSyncService intentionally NOT mocked — @Nullable injection → null → 503

    @MockBean
    private PriceService priceService;

    @MockBean
    private SystemStatsService systemStatsService;

    @MockBean
    private LoginActivityService loginActivityService;

    @MockBean
    private UserManagementService userManagementService;

    @MockBean
    private SystemConfigService systemConfigService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private SessionStateValidator sessionStateValidator;

    @Test
    void triggerPriceSync_whenFinnhubNotConfigured_returns503() throws Exception {
        mockMvc.perform(post("/api/v1/admin/prices/sync")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
}
