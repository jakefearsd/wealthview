package com.wealthview.api.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.audit.AuditLogService;
import com.wealthview.core.audit.dto.AuditLogResponse;
import com.wealthview.core.common.PageResponse;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.USER_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(AuditLogController.class)
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    private AuditLogResponse sampleAuditLog() {
        return new AuditLogResponse(
                UUID.randomUUID(), TENANT_ID, USER_ID,
                "CREATE", "Account", UUID.randomUUID(),
                Map.of("name", "Brokerage"), OffsetDateTime.now());
    }

    @Test
    void getAuditLogs_authenticated_returns200() throws Exception {
        var page = new PageResponse<>(List.of(sampleAuditLog()), 0, 50, 1L);
        when(auditLogService.getAuditLogs(eq(TENANT_ID), isNull(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/audit-log")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data[0].entity_type").value("Account"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void getAuditLogs_withEntityTypeFilter_returns200() throws Exception {
        var page = new PageResponse<>(List.of(sampleAuditLog()), 0, 50, 1L);
        when(auditLogService.getAuditLogs(eq(TENANT_ID), eq("Account"), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/audit-log")
                        .param("entity_type", "Account")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].entity_type").value("Account"));
    }

    @Test
    void getAuditLogs_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/audit-log"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAuditLogs_withCustomPageSize_returns200() throws Exception {
        var page = new PageResponse<AuditLogResponse>(List.of(), 0, 10, 0L);
        when(auditLogService.getAuditLogs(eq(TENANT_ID), isNull(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/audit-log")
                        .param("page", "0")
                        .param("size", "10")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }
}
