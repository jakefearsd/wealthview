package com.wealthview.api.controller;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.property.PropertyAnalyticsService;
import com.wealthview.core.property.PropertyRoiService;
import com.wealthview.core.property.PropertyService;
import com.wealthview.core.property.PropertyValuationService;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests PropertyController's 503 branches for endpoints that depend on
 * {@link com.wealthview.core.property.PropertyValuationSyncService}.
 *
 * <p>Uses a separate test class without @MockitoBean for PropertyValuationSyncService
 * so that the @Nullable injection resolves to null, triggering the 503 paths.
 */
@WealthViewControllerTest(PropertyController.class)
class PropertyControllerNoSyncServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PropertyService propertyService;

    @MockitoBean
    private PropertyValuationService valuationService;

    @MockitoBean
    private PropertyAnalyticsService analyticsService;

    @MockitoBean
    private PropertyRoiService roiService;

    // PropertyValuationSyncService intentionally NOT mocked — @Nullable → null → 503


    private static final UUID PROPERTY_ID = UUID.randomUUID();

    @Test
    void refreshValuation_whenSyncServiceUnavailable_returns503WithErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/properties/{id}/valuations/refresh", PROPERTY_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void selectZpid_whenSyncServiceUnavailable_returns503WithErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/properties/{id}/valuations/select-zpid", PROPERTY_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zpid": "12345"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));
    }
}
