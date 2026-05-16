package com.wealthview.app.it.auth;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation must apply identically whether the principal was
 * resolved via the cookie or the Bearer header. The Hibernate tenant filter
 * keys off the security-context tenant id, which the {@code
 * JwtAuthenticationFilter} populates the same way for both transports —
 * but a regression here would silently leak data across tenants, so it's
 * worth pinning explicitly.
 */
class BearerTenantIsolationIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_PASSWORD = "testpass123";

    private String tenant1Bearer;
    private String tenant2Bearer;
    private String tenant2AccountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.bootstrapSecondTenant(restTemplate);

        // Mobile-style logins for both tenants.
        tenant1Bearer = mobileLoginAs("it-admin@wealthview.test", ADMIN_PASSWORD);
        tenant2Bearer = mobileLoginAs("it-admin2@wealthview.test", ADMIN_PASSWORD);

        // Tenant 2 owns one account; tenant 1 owns one account.
        createAccountWithBearer(tenant1Bearer, "Tenant 1 Brokerage");
        tenant2AccountId = createAccountWithBearer(tenant2Bearer, "Tenant 2 Brokerage");
    }

    @Test
    void bearerAuth_cannotAccessOtherTenantData() {
        var response = restTemplate.exchange(
                "/api/v1/accounts/" + tenant2AccountId,
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(tenant1Bearer)), MAP_TYPE);

        // Cross-tenant lookups must return 404 (not 403) so we don't leak
        // existence of the resource — same convention as the cookie path.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bearerAuth_listAccountsReturnsOnlyOwnTenant() {
        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(tenant1Bearer)),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("name")).isEqualTo("Tenant 1 Brokerage");
    }

    @Test
    void bearerAuth_cannotMutateOtherTenantResource() {
        var headers = bearerHeaders(tenant1Bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = Map.of("name", "Pwned by tenant 1", "type", "brokerage");

        var response = restTemplate.exchange(
                "/api/v1/accounts/" + tenant2AccountId,
                HttpMethod.PUT, new HttpEntity<>(body, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String mobileLoginAs(String email, String password) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = restTemplate.exchange("/api/v1/auth/token/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", password), headers),
                MAP_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    private String createAccountWithBearer(String bearerToken, String name) {
        var headers = bearerHeaders(bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = Map.of("name", name, "type", "brokerage");
        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private HttpHeaders bearerHeaders(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
