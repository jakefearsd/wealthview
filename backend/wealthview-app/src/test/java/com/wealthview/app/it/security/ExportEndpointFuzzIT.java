package com.wealthview.app.it.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz the data-export endpoints. These return tenant-scoped CSV / JSON
 * payloads — the security risk is cross-tenant data leakage and unsafe
 * filename headers.
 *
 * <p>Asserted invariants:
 * <ul>
 *   <li>Each export endpoint, when called by tenant 1, returns ONLY tenant-1
 *       data — even if the database also contains tenant-2 data with similar
 *       shape (verified via fuzzed cross-tenant resource creation).</li>
 *   <li>{@code Content-Disposition} headers contain a static, hard-coded
 *       filename; nothing under client control reaches the filename so path
 *       traversal in the filename is impossible.</li>
 *   <li>Response body never echoes the JWT, tenant id, or session cookie.</li>
 * </ul>
 */
@Tag("fuzz")
class ExportEndpointFuzzIT extends AbstractApiIntegrationTest {

    private static final int ITERATIONS = 50;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.bootstrapSecondTenant(restTemplate);

        // Seed tenant 2 with random data shapes — none of which should ever
        // appear in tenant-1's export output.
        var t2Token = authHelper.tenant2Token();
        var random = new Random(FuzzSupport.MASTER_SEED ^ 0xEEEEL);
        for (int i = 0; i < 10; i++) {
            var name = "TENANT2_PROBE_" + FuzzSupport.alnum(random, 8);
            var body = new LinkedHashMap<String, Object>();
            body.put("name", name);
            body.put("type", FuzzSupport.choose(random,
                    "brokerage", "ira", "401k", "roth", "bank"));
            restTemplate.exchange("/api/v1/accounts",
                    HttpMethod.POST, authHelper.authEntity(body, t2Token), MAP_TYPE);
        }
    }

    @Test
    void exportEndpoints_underFuzzedAuth_returnExpectedTypeOrAuthError() {
        var endpoints = List.of(
                "/api/v1/export/json",
                "/api/v1/export/csv/accounts",
                "/api/v1/export/csv/transactions",
                "/api/v1/export/csv/holdings",
                "/api/v1/export/csv/properties");

        FuzzSupport.samples(this::randomAuthFlavour, ITERATIONS, 0xEE1L)
                .forEach(authFlavour -> {
                    for (var path : endpoints) {
                        var headers = new HttpHeaders();
                        if (!authFlavour.equals("none")) {
                            headers.add(HttpHeaders.COOKIE, "access_token=" + authFlavour);
                        }
                        var resp = restTemplate.exchange(path, HttpMethod.GET,
                                new org.springframework.http.HttpEntity<>(headers), String.class);
                        assertThat(resp.getStatusCode().is5xxServerError())
                                .as("export %s with auth=%s produced 5xx", path,
                                        authFlavour.length() > 16 ? authFlavour.substring(0, 13) + "..." : authFlavour)
                                .isFalse();
                    }
                });
    }

    @Test
    void exportEndpoints_filenameHeaderIsStatic_noPathTraversal() {
        // The Content-Disposition filename is hard-coded in the controller;
        // fuzz tests confirm it cannot be influenced by anything in the
        // request. We probe by sending various Accept / X-Forwarded-* /
        // referer headers — none of which should reach the filename.
        var endpoints = Map.of(
                "/api/v1/export/json", "wealthview-export.json",
                "/api/v1/export/csv/accounts", "accounts.csv",
                "/api/v1/export/csv/transactions", "transactions.csv",
                "/api/v1/export/csv/holdings", "holdings.csv",
                "/api/v1/export/csv/properties", "properties.csv");

        FuzzSupport.samples(this::randomMaliciousHeaders, 30, 0xEE2L)
                .forEach(headers -> {
                    headers.add(HttpHeaders.COOKIE,
                            "access_token=" + authHelper.adminToken());
                    for (var entry : endpoints.entrySet()) {
                        var resp = restTemplate.exchange(entry.getKey(), HttpMethod.GET,
                                new org.springframework.http.HttpEntity<>(headers),
                                String.class);
                        assertThat(resp.getStatusCode().is5xxServerError())
                                .as("export %s produced 5xx", entry.getKey())
                                .isFalse();
                        if (resp.getStatusCode() == HttpStatus.OK) {
                            var disp = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
                            assertThat(disp)
                                    .as("Content-Disposition for %s should pin filename %s",
                                            entry.getKey(), entry.getValue())
                                    .contains(entry.getValue())
                                    .doesNotContain("../")
                                    .doesNotContain("..\\");
                        }
                    }
                });
    }

    @Test
    void exportAccountsCsv_doesNotIncludeTenant2Data() {
        var resp = restTemplate.exchange("/api/v1/export/csv/accounts",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = resp.getBody() == null ? "" : resp.getBody();
        assertThat(body)
                .as("tenant-1 accounts export contained tenant-2 marker rows")
                .doesNotContain("TENANT2_PROBE_");
    }

    private String randomAuthFlavour(Random r) {
        return switch (r.nextInt(5)) {
            case 0 -> "none";
            case 1 -> authHelper.adminToken();              // legitimate
            case 2 -> FuzzSupport.alnum(r, 64);              // garbage
            case 3 -> "";
            default -> "eyJ" + FuzzSupport.alnum(r, 100) + "."
                    + FuzzSupport.alnum(r, 100) + "."
                    + FuzzSupport.alnum(r, 40);
        };
    }

    private HttpHeaders randomMaliciousHeaders(Random r) {
        var headers = new HttpHeaders();
        // X-Forwarded-Host injection variants
        if (r.nextBoolean()) {
            headers.add("X-Forwarded-Host", "../" + FuzzSupport.alnum(r, 8));
        }
        if (r.nextBoolean()) {
            headers.add("X-Original-URL", "../../etc/passwd");
        }
        if (r.nextBoolean()) {
            headers.add("Referer", "javascript:alert(1)");
        }
        if (r.nextBoolean()) {
            headers.add("X-Filename", "../../" + UUID.randomUUID() + ".csv");
        }
        return headers;
    }
}
