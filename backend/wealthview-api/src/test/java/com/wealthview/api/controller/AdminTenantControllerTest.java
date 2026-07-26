package com.wealthview.api.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.TestEntityHelper;
import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.tenant.TenantService;
import com.wealthview.core.tenant.dto.TenantDetailResponse;
import com.wealthview.persistence.entity.TenantEntity;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(AdminTenantController.class)
class AdminTenantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantService tenantService;


    private TenantEntity createTenantEntity() {
        var tenant = new TenantEntity("Test Tenant");
        TestEntityHelper.setId(tenant, UUID.randomUUID());
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
    void updateTenantActive_superAdmin_returns204() throws Exception {
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
