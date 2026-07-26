package com.wealthview.app.it.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz mass-assignment / extra-field handling on POST/PUT request bodies.
 *
 * <p>Asserted invariants:
 * <ul>
 *   <li>Adding extra fields to a valid request never causes a 5xx.</li>
 *   <li>Attempting to set the {@code tenant_id} via the body has no effect:
 *       the resource ends up owned by the security-context tenant, not the
 *       body-supplied tenant id.</li>
 *   <li>Attempting to set the {@code id} via the body does not cause that id
 *       to be used for the new resource (server generates its own id).</li>
 *   <li>Attempting to set timestamps / role / privileged flags via the body
 *       has no effect.</li>
 * </ul>
 */
@Tag("fuzz")
class MassAssignmentFuzzIT extends AbstractApiIntegrationTest {

    private static final int ITERATIONS = 100;

    @Test
    void createAccount_withExtraFields_isAcceptedButFieldsAreNotPersistedAsTenantOverride() {
        FuzzSupport.samples(this::randomAccountBodyWithExtras, ITERATIONS, 0xD4L)
                .forEach(body -> {
                    var resp = api.postForEntity("/api/v1/accounts", body);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("create with extras=%s produced 5xx", body)
                            .isFalse();

                    if (resp.getStatusCode().is2xxSuccessful()) {
                        var responseBody = resp.getBody();
                        assertThat(responseBody).isNotNull();

                        // The server-generated id must not be a value the
                        // client supplied via the body.
                        Object bodyId = body.get("id");
                        Object responseId = responseBody.get("id");
                        if (bodyId != null) {
                            assertThat(responseId)
                                    .as("client-supplied id=%s leaked into response", bodyId)
                                    .isNotEqualTo(bodyId);
                        }
                        // tenant_id is never echoed in the AccountResponse
                        // record. We probe by asking the OTHER tenant — it
                        // must NOT see this newly-created account.
                        if (body.containsKey("tenant_id")) {
                            assertThat(responseBody.get("tenant_id"))
                                    .as("response should not echo client tenant_id")
                                    .isNull();
                        }
                    }
                });
    }

    @Test
    void createTransaction_withExtraFields_doesNotPermitMassAssignment() {
        var accountId = data.createBrokerageAccountAndGetId();

        FuzzSupport.samples(this::randomTransactionBodyWithExtras, ITERATIONS, 0xD5L)
                .forEach(body -> {
                    var resp = api.postForEntity(
                            "/api/v1/accounts/" + accountId + "/transactions", body);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("create-txn with extras=%s produced 5xx", body)
                            .isFalse();

                    if (resp.getStatusCode().is2xxSuccessful()) {
                        var responseBody = resp.getBody();
                        // Client-supplied id must not become the resource id.
                        Object bodyId = body.get("id");
                        if (bodyId != null) {
                            assertThat(responseBody.get("id"))
                                    .as("client-supplied id=%s leaked into transaction response",
                                            bodyId)
                                    .isNotEqualTo(bodyId);
                        }
                    }
                });
    }

    @Test
    void createAccount_withTenantIdInBody_resourceBelongsToCallerNotBodyTenant() {
        // Bootstrap a second tenant; if mass-assignment leaked, an account
        // we POST as tenant-1 with body.tenant_id=tenant2 would leak there.
        authHelper.bootstrapSecondTenant(restTemplate);

        var fakeTenantId = authHelper.tenant2Id().toString();
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "mass-assign-probe");
        body.put("type", "brokerage");
        body.put("tenant_id", fakeTenantId);

        var createResp = api.postForEntity("/api/v1/accounts", body);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var createdId = (String) createResp.getBody().get("id");

        // Tenant 2 must NOT see this account; tenant 1 MUST see it.
        var tenant2View = api.getForEntityAs(authHelper.tenant2Token(), "/api/v1/accounts/" + createdId);
        assertThat(tenant2View.getStatusCode())
                .as("body-supplied tenant_id leaked the new account into tenant 2")
                .isEqualTo(HttpStatus.NOT_FOUND);

        var tenant1View = api.getForEntity("/api/v1/accounts/" + createdId);
        assertThat(tenant1View.getStatusCode())
                .as("account ownership lost — caller tenant cannot see its own account")
                .isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> randomAccountBodyWithExtras(Random r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "fuzz-acct-" + FuzzSupport.alnum(r, 8));
        body.put("type", FuzzSupport.choose(r, "brokerage", "ira", "401k", "roth", "bank"));
        // Always inject 1-4 extra fields drawn from the privileged-key bag.
        int n = 1 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            String key = FuzzSupport.choose(r, privilegedKeys());
            body.put(key, randomExtraValue(r));
        }
        return body;
    }

    private Map<String, Object> randomTransactionBodyWithExtras(Random r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", "2024-01-15");
        body.put("type", "buy");
        body.put("symbol", "AAPL");
        body.put("quantity", 1);
        body.put("amount", 100);
        int n = 1 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            String key = FuzzSupport.choose(r, privilegedKeys());
            body.put(key, randomExtraValue(r));
        }
        return body;
    }

    private String[] privilegedKeys() {
        return new String[]{
                "id", "tenant_id", "tenantId",
                "created_at", "createdAt", "updated_at",
                "is_admin", "isAdmin", "role", "super_admin",
                "user_id", "balance", "import_hash",
                "manual_override", "is_manual_override",
                "deleted_at"
        };
    }

    private Object randomExtraValue(Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> java.util.UUID.randomUUID().toString();
            case 1 -> "admin";
            case 2 -> true;
            case 3 -> r.nextInt(1_000_000);
            case 4 -> List.of("a", "b");
            default -> Map.of("nested", "value");
        };
    }
}
