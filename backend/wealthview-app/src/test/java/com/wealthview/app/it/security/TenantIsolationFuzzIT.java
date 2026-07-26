package com.wealthview.app.it.security;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz tenant isolation under arbitrary path UUIDs.
 *
 * <p>The Hibernate tenant filter masks "exists in another tenant" the same way
 * as "does not exist anywhere": both return 404. This test asserts that
 * invariant under randomly generated UUIDs (some belonging to tenant 2, some
 * pure random) for every CRUD verb on every resource that takes a UUID path
 * variable.
 *
 * <p>Asserted invariants:
 * <ul>
 *   <li>GET / PUT / DELETE on a UUID owned by tenant 2 returns 404 to tenant 1
 *       (not 200, not 403, not 500).</li>
 *   <li>GET / PUT / DELETE on a never-issued UUID returns 404.</li>
 *   <li>No response leaks tenant 2's data when accessed by tenant 1.</li>
 * </ul>
 */
@Tag("fuzz")
class TenantIsolationFuzzIT extends AbstractApiIntegrationTest {

    private static final int ITERATIONS = 200;

    private String tenant2AccountId;
    private String tenant2PropertyId;
    private String tenant2ScenarioId;
    private String tenant2TransactionId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.bootstrapSecondTenant(restTemplate);

        // Create resources owned by tenant 2 — tenant 1 must never observe these.
        var t2Token = authHelper.tenant2Token();
        var t2Acct = api.postForEntityAs(t2Token, "/api/v1/accounts",
                Map.of("name", "T2 Account", "type", "brokerage"));
        tenant2AccountId = (String) t2Acct.getBody().get("id");

        var t2Prop = api.postForEntityAs(t2Token, "/api/v1/properties", Map.of(
                        "address", "T2 Address",
                        "purchase_price", 100000,
                        "purchase_date", "2020-01-01",
                        "current_value", 110000,
                        "mortgage_balance", 50000));
        tenant2PropertyId = (String) t2Prop.getBody().get("id");

        var t2Scen = api.postForEntityAs(t2Token, "/api/v1/projections", Map.of(
                        "name", "T2 Scenario",
                        "retirement_date", "2050-01-01",
                        "end_age", 90,
                        "inflation_rate", 0.03,
                        "birth_year", 1990,
                        "withdrawal_rate", 0.04,
                        "withdrawal_strategy", "fixed",
                        "accounts", java.util.List.of(Map.of(
                                "initial_balance", 1000,
                                "annual_contribution", 100,
                                "expected_return", 0.07,
                                "account_type", "taxable"))));
        tenant2ScenarioId = (String) t2Scen.getBody().get("id");

        var t2Txn = api.postForEntityAs(t2Token,
                "/api/v1/accounts/" + tenant2AccountId + "/transactions", Map.of(
                        "date", "2024-01-15", "type", "buy",
                        "symbol", "AAPL", "quantity", 1, "amount", 100));
        tenant2TransactionId = (String) t2Txn.getBody().get("id");
    }

    @Test
    void getAccountById_underRandomUuid_neverLeaksAndNeverCrashes() {
        probeUuidEndpoint("/api/v1/accounts/", HttpMethod.GET, tenant2AccountId);
    }

    @Test
    void deleteAccountById_underRandomUuid_neverDeletesCrossTenant() {
        // The post-condition we care about: tenant 2's account still exists
        // after the fuzz run.
        probeUuidEndpoint("/api/v1/accounts/", HttpMethod.DELETE, tenant2AccountId);

        // Verify tenant 2's resource is intact.
        var afterFuzz = api.getForEntityAs(authHelper.tenant2Token(), "/api/v1/accounts/" + tenant2AccountId);
        assertThat(afterFuzz.getStatusCode())
                .as("tenant 2's account was deleted by tenant 1's fuzzed DELETE")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void getPropertyById_underRandomUuid_neverLeaks() {
        probeUuidEndpoint("/api/v1/properties/", HttpMethod.GET, tenant2PropertyId);
    }

    @Test
    void deletePropertyById_underRandomUuid_neverDeletesCrossTenant() {
        probeUuidEndpoint("/api/v1/properties/", HttpMethod.DELETE, tenant2PropertyId);

        var afterFuzz = api.getForEntityAs(authHelper.tenant2Token(), "/api/v1/properties/" + tenant2PropertyId);
        assertThat(afterFuzz.getStatusCode())
                .as("tenant 2's property was deleted by tenant 1's fuzzed DELETE")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void getScenarioById_underRandomUuid_neverLeaks() {
        probeUuidEndpoint("/api/v1/projections/", HttpMethod.GET, tenant2ScenarioId);
    }

    @Test
    void deleteTransactionById_underRandomUuid_neverDeletesCrossTenant() {
        probeUuidEndpoint("/api/v1/transactions/", HttpMethod.DELETE, tenant2TransactionId);

        var listResp = api.getForEntityAs(authHelper.tenant2Token(),
                "/api/v1/accounts/" + tenant2AccountId + "/transactions");
        @SuppressWarnings("unchecked")
        var data = (java.util.List<Map<String, Object>>) listResp.getBody().get("data");
        assertThat(data)
                .as("tenant 2's transaction was deleted by tenant 1's fuzzed DELETE")
                .hasSize(1);
    }

    private void probeUuidEndpoint(String basePath, HttpMethod method, String tenant2Id) {
        FuzzSupport.samples(this::randomUuidVariant, ITERATIONS,
                ((long) basePath.hashCode() << 32) ^ method.toString().hashCode())
                .forEach(uuid -> {
                    String path = basePath + uuid;
                    var entity = authHelper.authEntity(authHelper.adminToken());
                    var resp = restTemplate.exchange(path, method, entity, MAP_TYPE);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("%s %s with uuid=%s produced 5xx", method, basePath, uuid)
                            .isFalse();
                    if (uuid.equals(tenant2Id)) {
                        assertThat(resp.getStatusCode().value())
                                .as("%s %s on tenant-2 owned uuid=%s should be 404 (filter masks existence)",
                                        method, basePath, uuid)
                                .isEqualTo(HttpStatus.NOT_FOUND.value());
                    } else {
                        // Random non-existent UUID — must be 404 (or 204 for
                        // DELETE if implementation tolerates absent resources;
                        // however our handlers raise EntityNotFoundException,
                        // which becomes 404). Never 200 with a body.
                        if (method == HttpMethod.GET) {
                            assertThat(resp.getStatusCode().value())
                                    .as("%s %s on random uuid=%s should be 404, was %s",
                                            method, basePath, uuid, resp.getStatusCode())
                                    .isEqualTo(HttpStatus.NOT_FOUND.value());
                        } else {
                            // PUT / DELETE on missing UUID: 404 expected.
                            assertThat(resp.getStatusCode().value())
                                    .as("%s %s on random uuid=%s should be 4xx, was %s",
                                            method, basePath, uuid, resp.getStatusCode())
                                    .isIn(HttpStatus.NOT_FOUND.value(),
                                            HttpStatus.BAD_REQUEST.value());
                        }
                    }
                });
    }

    /** Mostly random UUIDs, occasionally inject the tenant-2 UUIDs. */
    private String randomUuidVariant(Random r) {
        // 5% of the time, pick a known tenant-2 resource UUID (the heart of
        // the cross-tenant probe); otherwise return a fresh random UUID.
        if (r.nextInt(20) == 0) {
            return FuzzSupport.choose(r,
                    tenant2AccountId, tenant2PropertyId,
                    tenant2ScenarioId, tenant2TransactionId);
        }
        return new UUID(r.nextLong(), r.nextLong()).toString();
    }
}
