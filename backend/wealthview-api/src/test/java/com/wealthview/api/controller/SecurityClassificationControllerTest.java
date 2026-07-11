package com.wealthview.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.projection.SecurityClassificationService;
import com.wealthview.core.projection.dto.AssetClass;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedViewer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityClassificationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class SecurityClassificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SecurityClassificationService classificationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SessionStateValidator sessionStateValidator;

    @Test
    void setClassification_validRequest_returns200() throws Exception {
        when(classificationService.setOverride(TENANT_ID, "VXUS", AssetClass.INTL_STOCK))
                .thenReturn(AssetClass.INTL_STOCK);

        mockMvc.perform(put("/api/v1/securities/{symbol}/classification", "VXUS")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"asset_class": "INTL_STOCK"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("VXUS"))
                .andExpect(jsonPath("$.asset_class").value("intl_stock"));

        verify(classificationService).setOverride(TENANT_ID, "VXUS", AssetClass.INTL_STOCK);
    }

    @Test
    void setClassification_lowerCaseAssetClass_isAccepted() throws Exception {
        when(classificationService.setOverride(TENANT_ID, "VXUS", AssetClass.INTL_STOCK))
                .thenReturn(AssetClass.INTL_STOCK);

        mockMvc.perform(put("/api/v1/securities/{symbol}/classification", "VXUS")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"asset_class": "intl_stock"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset_class").value("intl_stock"));
    }

    @Test
    void setClassification_unknownAssetClass_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/securities/{symbol}/classification", "VXUS")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"asset_class": "BOGUS"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void setClassification_blankAssetClass_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/securities/{symbol}/classification", "VXUS")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"asset_class": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setClassification_viewerRole_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/securities/{symbol}/classification", "VXUS")
                        .with(authenticatedViewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"asset_class": "INTL_STOCK"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void setClassification_unauthenticated_returns403() throws Exception {
        // Mutating requests hit Spring Security's CsrfFilter before the auth check, so an
        // unauthenticated PUT with no CSRF token is rejected 403 rather than 401 (matches
        // ImportControllerTest#importCsv_unauthenticated_returns403 for the same reason).
        mockMvc.perform(put("/api/v1/securities/{symbol}/classification", "VXUS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"asset_class": "INTL_STOCK"}
                                """))
                .andExpect(status().isForbidden());
    }
}
