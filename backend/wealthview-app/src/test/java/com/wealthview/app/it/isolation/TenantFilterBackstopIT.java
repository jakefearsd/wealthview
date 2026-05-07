package com.wealthview.app.it.isolation;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.core.auth.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;

/**
 * Proves that the {@code tenantFilter} Hibernate filter masks cross-tenant
 * rows even when a repository call has no {@code tenantId} predicate. This is
 * the defense-in-depth backstop against an accidentally-introduced IDOR.
 */
@Import(TenantFilterBackstopProbeConfig.class)
class TenantFilterBackstopIT extends AbstractApiIntegrationTest {

    @Autowired
    private TenantFilterBackstopProbe probe;

    private UUID tenant1AccountId;
    private UUID tenant2AccountId;
    private UUID tenant1UserId;
    private UUID tenant1Id;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.bootstrapSecondTenant(restTemplate);

        tenant1Id = authHelper.tenantId();
        tenant1UserId = authHelper.adminUserId();

        // Create one account per tenant via the API
        var account1 = restTemplate.exchange("/api/v1/accounts", POST,
                authHelper.authEntity(java.util.Map.of("name", "T1 Acct", "type", "brokerage"),
                        authHelper.adminToken()), MAP_TYPE);
        tenant1AccountId = UUID.fromString((String) account1.getBody().get("id"));

        var account2 = restTemplate.exchange("/api/v1/accounts", POST,
                authHelper.authEntity(java.util.Map.of("name", "T2 Acct", "type", "brokerage"),
                        authHelper.tenant2Token()), MAP_TYPE);
        tenant2AccountId = UUID.fromString((String) account2.getBody().get("id"));
    }

    @Test
    void buggyRepositoryCall_isBlockedByFilter_forCrossTenantId() {
        authenticateAs(tenant1UserId, tenant1Id, "MEMBER");

        // Tenant 1 asks for tenant 2's account using the IDOR-prone findById.
        var result = probe.findByIdAsQueryIgnoringTenant(tenant2AccountId);

        assertThat(result)
                .as("filter must mask tenant 2's row from a tenant 1 caller")
                .isEmpty();
    }

    @Test
    void buggyRepositoryCall_returnsRow_forOwnTenantId() {
        authenticateAs(tenant1UserId, tenant1Id, "MEMBER");

        var result = probe.findByIdAsQueryIgnoringTenant(tenant1AccountId);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getId()).isEqualTo(tenant1AccountId);
    }

    @Test
    void crossTenantAnnotated_seesAcrossTenants() {
        authenticateAs(tenant1UserId, tenant1Id, "MEMBER");

        var result = probe.findByIdAsQueryCrossTenant(tenant2AccountId);

        assertThat(result)
                .as("@CrossTenant must disable the filter so the row is visible")
                .isPresent();
        assertThat(result.orElseThrow().getId()).isEqualTo(tenant2AccountId);
    }

    @Test
    void filterIsRestoredAfterCrossTenantMethodReturns() {
        authenticateAs(tenant1UserId, tenant1Id, "MEMBER");

        // First, prove cross-tenant call works
        assertThat(probe.findByIdAsQueryCrossTenant(tenant2AccountId)).isPresent();

        // Then, prove filter is back on for the next regular call
        assertThat(probe.findByIdAsQueryIgnoringTenant(tenant2AccountId))
                .as("filter must be re-enabled after @CrossTenant returns")
                .isEmpty();
    }

    @Test
    void superAdminPrincipal_seesCrossTenantWithoutAnnotation() {
        authenticateAs(tenant1UserId, tenant1Id, "SUPER_ADMIN");

        var result = probe.findByIdAsQueryIgnoringTenant(tenant2AccountId);

        assertThat(result)
                .as("SUPER_ADMIN must see cross-tenant rows without @CrossTenant")
                .isPresent();
    }

    @Test
    void findAll_returnsOnlyOwnTenantRows() {
        authenticateAs(tenant1UserId, tenant1Id, "MEMBER");

        var all = probe.findAllIgnoringTenant();

        assertThat(all)
                .as("findAll must only return tenant 1's rows")
                .extracting(a -> a.getId())
                .containsExactlyInAnyOrder(tenant1AccountId);
    }

    @Test
    void noAuthenticationContext_filterDisabled_seesAllRows() {
        SecurityContextHolder.clearContext();

        // Mimics a scheduled job context — no security context.
        var result = probe.findByIdAsQueryIgnoringTenant(tenant2AccountId);

        assertThat(result)
                .as("background jobs (no security context) must read all rows")
                .isPresent();
    }

    private void authenticateAs(UUID userId, UUID tenantId, String role) {
        var principal = new TestPrincipal(userId, tenantId, role);
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private record TestPrincipal(UUID userId, UUID tenantId, String role)
            implements TenantContext.AuthenticatedUser {
    }
}
