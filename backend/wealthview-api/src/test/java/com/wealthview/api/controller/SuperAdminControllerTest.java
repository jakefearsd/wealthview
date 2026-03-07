package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.tenant.TenantService;
import com.wealthview.core.tenant.dto.TenantDetailResponse;
import com.wealthview.persistence.entity.TenantEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import java.time.OffsetDateTime;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SuperAdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class SuperAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private TenantEntity createTenantEntity() throws Exception {
        var tenant = new TenantEntity("Test Tenant");
        Field idField = TenantEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(tenant, UUID.randomUUID());
        return tenant;
    }

    @Test
    void createTenant_superAdmin_returns201() throws Exception {
        when(tenantService.createTenant(any())).thenReturn(createTenantEntity());

        mockMvc.perform(post("/api/v1/admin/tenants")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "New Tenant"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Tenant"));
    }

    @Test
    void listTenants_superAdmin_returns200() throws Exception {
        when(tenantService.getAllTenants()).thenReturn(List.of(createTenantEntity()));

        mockMvc.perform(get("/api/v1/admin/tenants")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test Tenant"));
    }

    @Test
    void createTenant_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tenants")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "New Tenant"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void listTenants_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants")
                        .with(authenticatedAdmin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listTenantDetails_superAdmin_returns200() throws Exception {
        var tenantId = UUID.randomUUID();
        var detail = new TenantDetailResponse(tenantId, "Test", true, 3, 5, OffsetDateTime.now());
        when(tenantService.getAllTenantDetails()).thenReturn(List.of(detail));

        mockMvc.perform(get("/api/v1/admin/tenants/details")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test"))
                .andExpect(jsonPath("$[0].user_count").value(3))
                .andExpect(jsonPath("$[0].account_count").value(5));
    }

    @Test
    void getTenantDetail_superAdmin_returns200() throws Exception {
        var tenantId = UUID.randomUUID();
        var detail = new TenantDetailResponse(tenantId, "Test", true, 2, 3, OffsetDateTime.now());
        when(tenantService.getTenantDetail(tenantId)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/admin/tenants/{id}", tenantId)
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test"))
                .andExpect(jsonPath("$.is_active").value(true));
    }

    @Test
    void getTenantDetail_notFound_returns404() throws Exception {
        var tenantId = UUID.randomUUID();
        when(tenantService.getTenantDetail(tenantId))
                .thenThrow(new EntityNotFoundException("Tenant not found"));

        mockMvc.perform(get("/api/v1/admin/tenants/{id}", tenantId)
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void setTenantActive_superAdmin_returns204() throws Exception {
        var tenantId = UUID.randomUUID();
        doNothing().when(tenantService).setTenantActive(tenantId, false);

        mockMvc.perform(put("/api/v1/admin/tenants/{id}/active", tenantId)
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false}
                                """))
                .andExpect(status().isNoContent());
    }
}
