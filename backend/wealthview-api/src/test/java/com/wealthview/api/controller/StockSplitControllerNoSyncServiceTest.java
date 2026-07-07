package com.wealthview.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.split.StockSplitService;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the StockSplitController.sync() 503 branch: when StockSplitSyncService
 * is not available (Finnhub not configured), the endpoint returns 503.
 *
 * <p>Uses a separate test class without @MockitoBean for StockSplitSyncService
 * so that Spring's ObjectProvider.getIfAvailable() returns null, triggering
 * the service-unavailable branch.
 */
@WebMvcTest(StockSplitController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class StockSplitControllerNoSyncServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockSplitService stockSplitService;

    // Note: StockSplitSyncService is intentionally NOT mocked here,
    // so ObjectProvider.getIfAvailable() returns null → 503 branch is exercised.

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SessionStateValidator sessionStateValidator;

    @Test
    void sync_whenServiceUnavailable_returns503() throws Exception {
        mockMvc.perform(post("/api/v1/admin/stock-splits/sync")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isServiceUnavailable());
    }
}
