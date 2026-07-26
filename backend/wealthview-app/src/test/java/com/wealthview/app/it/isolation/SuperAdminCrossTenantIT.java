package com.wealthview.app.it.isolation;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that SUPER_ADMIN-only endpoints can read data across tenant
 * boundaries with the {@code tenantFilter} disabled, while regular ADMIN /
 * MEMBER tokens cannot reach those endpoints (or, where tenant-scoped
 * admin endpoints exist, see only their own tenant's data).
 */
class SuperAdminCrossTenantIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_PASS = "superpass123";

    private AuthHelper.Session superAdminSession;
    private String superAdminToken;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.bootstrapSecondTenant(restTemplate);

        // Create accounts in both tenants
        api.postForEntity("/api/v1/accounts", Map.of("name", "T1 Acct", "type", "brokerage"));
        api.postForEntityAs(authHelper.tenant2Token(), "/api/v1/accounts",
                Map.of("name", "T2 Acct", "type", "brokerage"));

        // Create a SUPER_ADMIN user (lives in tenant 1 but role grants cross-tenant)
        var saEmail = "super-admin@wealthview.test";
        authHelper.createSuperAdminDirectly(saEmail, SUPER_ADMIN_PASS);
        superAdminSession = authHelper.loginAsSession(restTemplate, saEmail, SUPER_ADMIN_PASS);
        superAdminToken = superAdminSession.accessToken();
    }

    @Test
    void superAdminListsTenantsAcrossSystem() {
        var response = api.getListForEntityAs(superAdminToken, "/api/v1/admin/tenants");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("super admin must see both tenants")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void superAdminListsAllUsersAcrossTenants() {
        var response = api.getListForEntityAs(superAdminToken, "/api/v1/admin/users");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Both tenants' admin users + the super admin should all be visible
        assertThat(response.getBody())
                .as("super admin must see users from every tenant")
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void regularAdmin_cannotAccessSuperAdminTenantsEndpoint() {
        var response = api.getForEntity("/api/v1/admin/tenants", String.class);

        assertThat(response.getStatusCode())
                .as("non-SUPER_ADMIN must be forbidden from /api/v1/admin/tenants")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tenantAdmin_listingTenantUsers_seesOnlyOwnTenantUsers() {
        // Add a second user to tenant 1
        authHelper.createUserDirectly("member1@wealthview.test", "memberpass1", "member");

        var response = api.getListForEntity("/api/v1/tenant/users");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Tenant 1 admin should see tenant 1 users only (admin + member). The
        // SUPER_ADMIN user is also in tenant 1, so 3 in total.
        var emails = response.getBody().stream()
                .map(u -> (String) u.get("email"))
                .toList();
        assertThat(emails).noneMatch(e -> e != null && e.contains("it-admin2"));
    }
}
