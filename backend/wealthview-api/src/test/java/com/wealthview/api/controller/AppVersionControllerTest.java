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
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.mobile.MobileAppVersionService;
import com.wealthview.core.mobile.dto.VersionCheckResponse;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppVersionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class AppVersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MobileAppVersionService service;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private SessionStateValidator sessionStateValidator;

    private VersionCheckResponse upToDateResponse() {
        return new VersionCheckResponse("ios", "2.1.0", "2.0.0", "2.1.0",
                false, false, "https://apps.apple.com/app", null);
    }

    @Test
    void versionCheck_validPlatformAndVersion_returns200() throws Exception {
        when(service.versionCheck(eq("ios"), eq("2.1.0"))).thenReturn(upToDateResponse());

        mockMvc.perform(get("/api/v1/app/version-check")
                        .param("platform", "ios")
                        .param("version", "2.1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("ios"))
                .andExpect(jsonPath("$.update_required").value(false))
                .andExpect(jsonPath("$.update_recommended").value(false));
    }

    @Test
    void versionCheck_missingPlatform_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/app/version-check")
                        .param("version", "2.1.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void versionCheck_missingVersion_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/app/version-check")
                        .param("platform", "android"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void versionCheck_updateRequired_returns200WithFlag() throws Exception {
        var updateRequired = new VersionCheckResponse("android", "1.0.0", "2.0.0", "2.1.0",
                true, true, "https://play.google.com/store/apps", "Please update to continue");
        when(service.versionCheck(eq("android"), eq("1.0.0"))).thenReturn(updateRequired);

        mockMvc.perform(get("/api/v1/app/version-check")
                        .param("platform", "android")
                        .param("version", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.update_required").value(true))
                .andExpect(jsonPath("$.message").value("Please update to continue"));
    }
}
