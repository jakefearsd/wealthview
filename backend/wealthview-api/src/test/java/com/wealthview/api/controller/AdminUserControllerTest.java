package com.wealthview.api.controller;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.tenant.UserManagementService;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserManagementService userManagementService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SessionStateValidator sessionStateValidator;

    private TenantEntity createTenantEntity() throws Exception {
        var tenant = new TenantEntity("Test Tenant");
        Field idField = TenantEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(tenant, UUID.randomUUID());
        return tenant;
    }

    private UserEntity createUserEntity(TenantEntity tenant) throws Exception {
        var user = new UserEntity(tenant, "user@example.com", "hash", "member");
        Field idField = UserEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(user, UUID.randomUUID());
        return user;
    }

    @Test
    void getAllUsers_superAdmin_returns200() throws Exception {
        var tenant = createTenantEntity();
        var user = createUserEntity(tenant);
        when(userManagementService.getAllUsers()).thenReturn(List.of(user));

        mockMvc.perform(get("/api/v1/admin/users")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("user@example.com"))
                .andExpect(jsonPath("$[0].tenant_name").value("Test Tenant"));
    }

    @Test
    void getAllUsers_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(authenticatedAdmin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetPassword_superAdmin_returns204() throws Exception {
        var userId = UUID.randomUUID();
        doNothing().when(userManagementService).resetPasswordByUserId(eq(userId), anyString());

        mockMvc.perform(put("/api/v1/admin/users/{userId}/password", userId)
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"new_password": "newSecret123"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void resetPassword_shortPassword_returns400() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/admin/users/{userId}/password", userId)
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"new_password": "short"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_blankPassword_returns400() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/admin/users/{userId}/password", userId)
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"new_password": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_commonPassword_returns400() throws Exception {
        var userId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Password is too common"))
                .when(userManagementService).resetPasswordByUserId(eq(userId), eq("password1234"));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/password", userId)
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"new_password": "password1234"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_nonSuperAdmin_returns403() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/admin/users/{userId}/password", userId)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"new_password": "newSecret123"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void setUserActive_superAdmin_returns204() throws Exception {
        var userId = UUID.randomUUID();
        doNothing().when(userManagementService).setUserActiveById(eq(userId), anyBoolean());

        mockMvc.perform(put("/api/v1/admin/users/{userId}/active", userId)
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false}
                                """))
                .andExpect(status().isNoContent());
    }
}
