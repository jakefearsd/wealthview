package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.LoginActivityService;
import com.wealthview.core.auth.dto.LoginActivityResponse;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.config.SystemStatsService;
import com.wealthview.core.config.dto.SystemConfigResponse;
import com.wealthview.core.config.dto.SystemStatsResponse;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.price.PriceService;
import com.wealthview.core.price.dto.CsvImportResult;
import com.wealthview.core.price.dto.PriceResponse;
import com.wealthview.core.price.dto.PriceSyncStatus;
import com.wealthview.core.price.dto.YahooSyncResult;
import com.wealthview.core.tenant.TenantService;
import com.wealthview.core.tenant.UserManagementService;
import com.wealthview.core.tenant.dto.AdminUserResponse;
import com.wealthview.core.tenant.dto.TenantDetailResponse;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SuperAdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class SuperAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private PriceService priceService;

    @MockBean
    private SystemStatsService systemStatsService;

    @MockBean
    private LoginActivityService loginActivityService;

    @MockBean
    private UserManagementService userManagementService;

    @MockBean
    private SystemConfigService systemConfigService;

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

    // --- Existing tenant tests ---

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

    // --- Existing price tests ---

    @Test
    void getPriceStatus_superAdmin_returns200() throws Exception {
        var statuses = List.of(
                new PriceSyncStatus("AAPL", LocalDate.of(2024, 3, 20), "finnhub", false),
                new PriceSyncStatus("MSFT", LocalDate.of(2024, 3, 15), "manual", true));
        when(priceService.getSyncStatus()).thenReturn(statuses);

        mockMvc.perform(get("/api/v1/admin/prices/status")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].stale").value(false))
                .andExpect(jsonPath("$[1].symbol").value("MSFT"))
                .andExpect(jsonPath("$[1].stale").value(true));
    }

    @Test
    void getPriceStatus_admin_returns200() throws Exception {
        when(priceService.getSyncStatus()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/prices/status")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void syncFromYahoo_superAdmin_returns200() throws Exception {
        when(priceService.getSyncStatus()).thenReturn(
                List.of(new PriceSyncStatus("AAPL", LocalDate.now(), "finnhub", false)));
        when(priceService.syncFromYahoo(List.of("AAPL")))
                .thenReturn(new YahooSyncResult(2, 0, List.of()));

        mockMvc.perform(post("/api/v1/admin/prices/yahoo/sync")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.failures").isEmpty());
    }

    @Test
    void fetchFromYahoo_superAdmin_returns200() throws Exception {
        when(priceService.fetchFromYahoo(any())).thenReturn(List.of(
                new PriceResponse("AAPL", LocalDate.of(2024, 1, 2),
                        new BigDecimal("185.50"), "yahoo")));

        mockMvc.perform(post("/api/v1/admin/prices/yahoo/fetch")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbols": ["AAPL"],
                                    "from_date": "2024-01-01",
                                    "to_date": "2024-01-05"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].close_price").value(185.50));
    }

    @Test
    void saveYahooPrices_superAdmin_returns204() throws Exception {
        when(priceService.bulkUpsertPrices(any(), eq("yahoo"))).thenReturn(2);

        mockMvc.perform(post("/api/v1/admin/prices/yahoo/save")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prices": [
                                        {"symbol": "AAPL", "date": "2024-01-02", "close_price": 185.50},
                                        {"symbol": "MSFT", "date": "2024-01-02", "close_price": 370.25}
                                    ]
                                }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void importCsv_superAdmin_returns200() throws Exception {
        when(priceService.importCsv(any())).thenReturn(new CsvImportResult(3, List.of()));

        var file = new MockMultipartFile("file", "prices.csv", "text/csv",
                "symbol,date,close_price\nAAPL,2024-01-02,185.50".getBytes());

        mockMvc.perform(multipart("/api/v1/admin/prices/csv")
                        .file(file)
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void importCsv_admin_returns200() throws Exception {
        when(priceService.importCsv(any())).thenReturn(new CsvImportResult(0, List.of()));

        var file = new MockMultipartFile("file", "prices.csv", "text/csv",
                "symbol,date,close_price\nAAPL,2024-01-02,185.50".getBytes());

        mockMvc.perform(multipart("/api/v1/admin/prices/csv")
                        .file(file)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    // --- New: price browse and delete ---

    @Test
    void browseSymbolPrices_superAdmin_returns200() throws Exception {
        when(priceService.browseSymbol(eq("AAPL"), any(), any())).thenReturn(List.of(
                new PriceResponse("AAPL", LocalDate.of(2024, 1, 2), new BigDecimal("185.50"), "manual")));

        mockMvc.perform(get("/api/v1/admin/prices/AAPL/history")
                        .with(authenticatedSuperAdmin())
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].close_price").value(185.50));
    }

    @Test
    void deletePrice_superAdmin_returns204() throws Exception {
        doNothing().when(priceService).deletePrice("AAPL", LocalDate.of(2024, 1, 2));

        mockMvc.perform(delete("/api/v1/admin/prices/AAPL/2024-01-02")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePrice_notFound_returns404() throws Exception {
        doThrow(new EntityNotFoundException("Price not found"))
                .when(priceService).deletePrice(anyString(), any(LocalDate.class));

        mockMvc.perform(delete("/api/v1/admin/prices/AAPL/2024-01-02")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isNotFound());
    }

    // --- New: system stats ---

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

    // --- New: login activity ---

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

    // --- New: all users ---

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

    // --- New: password reset ---

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

    // --- New: user active toggle ---

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

    // --- New: system config ---

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
