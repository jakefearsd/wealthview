package com.wealthview.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.price.PriceService;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the AdminPriceController.triggerPriceSync() 503 branch: when
 * PriceSyncService is not available (Finnhub not configured), the endpoint
 * returns 503 with a descriptive error body.
 *
 * <p>Uses a separate test class without @MockitoBean for PriceSyncService
 * so that it is null (injected as @Nullable), triggering the 503 branch.
 */
@WealthViewControllerTest(AdminPriceController.class)
class AdminPriceControllerNoPriceSyncTest {

    @Autowired
    private MockMvc mockMvc;

    // PriceSyncService intentionally NOT mocked — @Nullable injection → null → 503

    @MockitoBean
    private PriceService priceService;


    @Test
    void triggerPriceSync_whenFinnhubNotConfigured_returns503() throws Exception {
        mockMvc.perform(post("/api/v1/admin/prices/sync")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
}
