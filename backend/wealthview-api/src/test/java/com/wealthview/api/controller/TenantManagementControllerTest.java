package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.tenant.TenantService;
import com.wealthview.core.tenant.UserManagementService;
import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.USER_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedMember;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantManagementController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class TenantManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private UserManagementService userManagementService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private TenantEntity createTenantEntity() throws Exception {
        var tenant = new TenantEntity("Test Tenant");
        setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private InviteCodeEntity createInviteCode() throws Exception {
        var tenant = createTenantEntity();
        var user = createUserEntity(tenant);
        var invite = new InviteCodeEntity(tenant, "ABC123", user, OffsetDateTime.now().plusDays(7));
        setField(invite, "id", UUID.randomUUID());
        return invite;
    }

    private UserEntity createUserEntity(TenantEntity tenant) throws Exception {
        var user = new UserEntity(tenant, "user@example.com", "hash", "member");
        setField(user, "id", UUID.randomUUID());
        return user;
    }

    // --- Existing tests ---

    @Test
    void generateInviteCode_admin_returns201() throws Exception {
        when(tenantService.generateInviteCode(TENANT_ID, USER_ID, 7))
                .thenReturn(createInviteCode());

        mockMvc.perform(post("/api/v1/tenant/invite-codes")
                        .with(authenticatedAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ABC123"));
    }

    @Test
    void generateInviteCode_withExpiryDays_returns201() throws Exception {
        when(tenantService.generateInviteCode(eq(TENANT_ID), eq(USER_ID), eq(14)))
                .thenReturn(createInviteCode());

        mockMvc.perform(post("/api/v1/tenant/invite-codes")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expiry_days": 14}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ABC123"));
    }

    @Test
    void generateInviteCode_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/tenant/invite-codes")
                        .with(authenticatedMember()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listInviteCodes_admin_returns200() throws Exception {
        when(tenantService.getInviteCodes(TENANT_ID))
                .thenReturn(List.of(createInviteCode()));

        mockMvc.perform(get("/api/v1/tenant/invite-codes")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ABC123"));
    }

    @Test
    void listUsers_admin_returns200() throws Exception {
        var tenant = createTenantEntity();
        when(userManagementService.getUsersForTenant(TENANT_ID))
                .thenReturn(List.of(createUserEntity(tenant)));

        mockMvc.perform(get("/api/v1/tenant/users")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("user@example.com"));
    }

    @Test
    void listUsers_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/tenant/users")
                        .with(authenticatedMember()))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateUserRole_admin_returns200() throws Exception {
        var tenant = createTenantEntity();
        when(userManagementService.updateUserRole(eq(TENANT_ID), any(UUID.class), eq("viewer")))
                .thenReturn(createUserEntity(tenant));

        var targetUserId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/tenant/users/{id}/role", targetUserId)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "viewer"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void updateUserRole_nonAdmin_returns403() throws Exception {
        var targetUserId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/tenant/users/{id}/role", targetUserId)
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "viewer"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_admin_returns204() throws Exception {
        var targetUserId = UUID.randomUUID();
        doNothing().when(userManagementService).deleteUser(TENANT_ID, targetUserId);

        mockMvc.perform(delete("/api/v1/tenant/users/{id}", targetUserId)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUser_nonAdmin_returns403() throws Exception {
        var targetUserId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/tenant/users/{id}", targetUserId)
                        .with(authenticatedMember()))
                .andExpect(status().isForbidden());
    }

    // --- New: revoke invite code ---

    @Test
    void revokeInviteCode_admin_returns204() throws Exception {
        var codeId = UUID.randomUUID();
        doNothing().when(tenantService).revokeInviteCode(TENANT_ID, codeId);

        mockMvc.perform(put("/api/v1/tenant/invite-codes/{id}/revoke", codeId)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void revokeInviteCode_nonAdmin_returns403() throws Exception {
        var codeId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/tenant/invite-codes/{id}/revoke", codeId)
                        .with(authenticatedMember()))
                .andExpect(status().isForbidden());
    }

    // --- New: delete used codes ---

    @Test
    void deleteUsedCodes_admin_returnsCount() throws Exception {
        when(tenantService.deleteUsedCodes(TENANT_ID)).thenReturn(3);

        mockMvc.perform(delete("/api/v1/tenant/invite-codes/used")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));
    }

    @Test
    void deleteUsedCodes_nonAdmin_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/tenant/invite-codes/used")
                        .with(authenticatedMember()))
                .andExpect(status().isForbidden());
    }
}
