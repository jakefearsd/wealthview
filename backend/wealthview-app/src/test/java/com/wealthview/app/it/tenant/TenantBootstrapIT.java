package com.wealthview.app.it.tenant;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the onboarding path for a brand-new tenant: a super admin creates the tenant and mints
 * its first invite code, the first registrant becomes the tenant's admin, and that admin can then
 * invite further users (who register as plain members).
 */
class TenantBootstrapIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_PASS = "superpass123";

    private String superAdminToken;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.createSuperAdminDirectly("bootstrap-root@wealthview.test", SUPER_ADMIN_PASS);
        superAdminToken = authHelper.loginAs(restTemplate, "bootstrap-root@wealthview.test", SUPER_ADMIN_PASS);
    }

    private String createTenant(String name) {
        var created = api.postForEntityAs(superAdminToken, "/api/v1/admin/tenants", Map.of("name", name));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private String registerAs(String email, String code) {
        var response = api.postAnonForEntity("/api/v1/auth/register",
                Map.of("email", email, "password", "mytestpass", "invite_code", code));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("role");
    }

    @Test
    void newTenant_fullOnboardingFlow_firstUserIsAdminAndLaterUsersAreMembers() {
        var tenantId = createTenant("Onboarding Tenant");

        var invite = api.postForEntityAs(superAdminToken,
                "/api/v1/admin/tenants/" + tenantId + "/invite-codes", null);
        assertThat(invite.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invite.getBody()).containsKeys("code", "expires_at");
        var firstCode = (String) invite.getBody().get("code");

        var firstRole = registerAs("first-user@wealthview.test", firstCode);
        assertThat(firstRole).isEqualTo("admin");

        var adminToken = authHelper.loginAs(restTemplate, "first-user@wealthview.test", "mytestpass");
        var tenantInvite = api.postForEntityAs(adminToken, "/api/v1/tenant/invite-codes", null);
        assertThat(tenantInvite.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var secondCode = (String) tenantInvite.getBody().get("code");

        var secondRole = registerAs("second-user@wealthview.test", secondCode);
        assertThat(secondRole).isEqualTo("member");
    }

    @Test
    void secondCodeFromSuperAdmin_registersAsMemberOnceTenantHasUsers() {
        var tenantId = createTenant("Two Codes Tenant");
        var first = api.postForEntityAs(superAdminToken,
                "/api/v1/admin/tenants/" + tenantId + "/invite-codes", null).getBody();
        var second = api.postForEntityAs(superAdminToken,
                "/api/v1/admin/tenants/" + tenantId + "/invite-codes", Map.of("expiry_days", 3)).getBody();

        var firstRole = registerAs("tc-first@wealthview.test", (String) first.get("code"));
        var secondRole = registerAs("tc-second@wealthview.test", (String) second.get("code"));

        assertThat(firstRole).isEqualTo("admin");
        assertThat(secondRole).isEqualTo("member");
    }

    @Test
    void generateInviteCode_unknownTenant_returns404() {
        var response = api.postForEntityAs(superAdminToken,
                "/api/v1/admin/tenants/00000000-0000-0000-0000-000000000000/invite-codes", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void generateInviteCode_inactiveTenant_returns409() {
        var tenantId = createTenant("Disabled Tenant");
        var disable = api.putForEntityAs(superAdminToken,
                "/api/v1/admin/tenants/" + tenantId + "/active", Map.of("active", false));
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var response = api.postForEntityAs(superAdminToken,
                "/api/v1/admin/tenants/" + tenantId + "/invite-codes", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void generateInviteCode_tenantAdminCannotUseAdminEndpoint_returns403() {
        var response = api.postForEntity(
                "/api/v1/admin/tenants/" + authHelper.tenantId() + "/invite-codes", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
