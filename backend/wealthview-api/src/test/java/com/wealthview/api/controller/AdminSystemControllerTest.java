package com.wealthview.api.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.auth.LoginActivityService;
import com.wealthview.core.auth.dto.LoginActivityResponse;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.config.SystemStatsService;
import com.wealthview.core.config.dto.SystemConfigResponse;
import com.wealthview.core.config.dto.SystemStatsResponse;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(AdminSystemController.class)
class AdminSystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemStatsService systemStatsService;

    @MockitoBean
    private LoginActivityService loginActivityService;

    @MockitoBean
    private SystemConfigService systemConfigService;


    @Test
    void getSystemStats_superAdmin_returns200() throws Exception {
        when(systemStatsService.getStats()).thenReturn(
                new SystemStatsResponse(10L, 8L, 3L, 25L, 100L, 500L, "N/A", 12L, 0L));

        mockMvc.perform(get("/api/v1/admin/system-stats")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_users").value(10))
                .andExpect(jsonPath("$.active_users").value(8))
                .andExpect(jsonPath("$.total_tenants").value(3));
    }

    @Test
    void getSystemStats_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system-stats")
                        .with(authenticatedAdmin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getLoginActivity_superAdmin_returns200() throws Exception {
        when(loginActivityService.getRecent(anyInt())).thenReturn(List.of(
                new LoginActivityResponse("user@example.com", UUID.randomUUID(),
                        true, "127.0.0.1", OffsetDateTime.now())));

        mockMvc.perform(get("/api/v1/admin/login-activity")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].user_email").value("user@example.com"))
                .andExpect(jsonPath("$[0].success").value(true));
    }

    @Test
    void getLoginActivity_withLimit_returns200() throws Exception {
        when(loginActivityService.getRecent(10)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/login-activity")
                        .with(authenticatedSuperAdmin())
                        .param("limit", "10"))
                .andExpect(status().isOk());
    }

    @Test
    void getConfig_superAdmin_returns200() throws Exception {
        when(systemConfigService.getAll()).thenReturn(List.of(
                new SystemConfigResponse("some.key", "some-value", OffsetDateTime.now())));

        mockMvc.perform(get("/api/v1/admin/config")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("some.key"))
                .andExpect(jsonPath("$[0].value").value("some-value"));
    }

    @Test
    void setConfig_superAdmin_returns204() throws Exception {
        doNothing().when(systemConfigService).set(anyString(), anyString());

        mockMvc.perform(put("/api/v1/admin/config/some.key")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value": "new-value"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void setConfig_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/config/some.key")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value": "new-value"}
                                """))
                .andExpect(status().isForbidden());
    }
}
