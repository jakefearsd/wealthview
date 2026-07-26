package com.wealthview.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.split.StockSplitService;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.errorEnvelope;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the StockSplitController.sync() 503 branch: when StockSplitSyncService
 * is not available (Finnhub not configured), the endpoint throws
 * {@code ServiceUnavailableException}, which {@code GlobalExceptionHandler}
 * maps to 503 with the standard {@code {error,message,status}} envelope.
 *
 * <p>Uses a separate test class without @MockitoBean for StockSplitSyncService
 * so the controller's @Nullable constructor parameter resolves to null,
 * triggering the service-unavailable branch.
 */
@WealthViewControllerTest(StockSplitController.class)
class StockSplitControllerNoSyncServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockSplitService stockSplitService;

    // Note: StockSplitSyncService is intentionally NOT mocked here, so the
    // controller's @Nullable field stays null → the service-unavailable branch is exercised.

    @Test
    void sync_whenServiceUnavailable_returns503WithStandardEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/admin/stock-splits/sync")
                        .with(authenticatedSuperAdmin()))
                .andExpect(errorEnvelope(HttpStatus.SERVICE_UNAVAILABLE))
                .andExpect(jsonPath("$.message").value(
                        "Stock split sync is not configured. Set app.finnhub.api-key in your environment."));
    }
}
