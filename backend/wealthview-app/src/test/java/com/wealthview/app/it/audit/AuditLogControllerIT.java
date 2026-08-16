package com.wealthview.app.it.audit;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for {@code /api/v1/audit-log}, which had no integration test.
 *
 * <p>Audit entries are not written inline: services publish an {@code AuditEvent} and
 * {@code AuditEventListener} persists it from a {@code @TransactionalEventListener} in a new
 * transaction. That indirection means the write path is only observable end-to-end — a unit test
 * of either half can pass while the two are not actually wired together.
 *
 * <p>Also pins the paging envelope ({@code data}/{@code page}/{@code size}/{@code total}) and the
 * {@code PageRequests} clamping that keeps a hostile {@code size} or {@code page} from reaching
 * Spring Data's int offset cast as a 500.
 */
class AuditLogControllerIT extends AbstractApiIntegrationTest {

    private static final String URL = "/api/v1/audit-log";
    private static final int MAX_PAGE_SIZE = 200;
    private static final String MEMBER_EMAIL = "it-audit-member@wealthview.test";
    private static final String VIEWER_EMAIL = "it-audit-viewer@wealthview.test";
    private static final String ROLE_TEST_PASSWORD = "testpass123";

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("data");
    }

    @Test
    void getAuditLogs_afterCreatingAnAccount_recordsACreateEntryForThatEntity() {
        var accountId = data.createBrokerageAccountAndGetId();

        var response = api.getForEntity(URL);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entriesOf(response.getBody()))
                .as("the account CREATE event must have been persisted by the transactional listener")
                .anySatisfy(entry -> {
                    assertThat(entry.get("action")).isEqualTo("CREATE");
                    assertThat(entry.get("entity_type")).isEqualTo("account");
                    assertThat(entry.get("entity_id")).isEqualTo(accountId);
                });
    }

    @Test
    void getAuditLogs_afterDeletingAnAccount_recordsBothCreateAndDelete() {
        var accountId = data.createBrokerageAccountAndGetId();
        api.deleteForEntity("/api/v1/accounts/" + accountId);

        var entries = entriesOf(api.getForEntity(URL).getBody());

        assertThat(entries).extracting(e -> e.get("action")).contains("CREATE", "DELETE");
    }

    @Test
    void getAuditLogs_returnsThePagingEnvelope() {
        data.createBrokerageAccountAndGetId();

        var body = api.getForEntity(URL).getBody();

        assertThat(body).containsKeys("data", "page", "size", "total");
        assertThat(body.get("page")).isEqualTo(0);
        assertThat(body.get("size")).isEqualTo(50);
        assertThat(((Number) body.get("total")).longValue()).isPositive();
    }

    @Test
    void getAuditLogs_filteredByEntityType_returnsOnlyThatType() {
        data.createBrokerageAccountAndGetId();
        data.createPropertyAndGetId();

        var entries = entriesOf(api.getForEntity(URL + "?entity_type=property").getBody());

        assertThat(entries).isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.get("entity_type")).isEqualTo("property"));
    }

    @Test
    void getAuditLogs_filteredByUnusedEntityType_returnsAnEmptyPage() {
        data.createBrokerageAccountAndGetId();

        var body = api.getForEntity(URL + "?entity_type=no_such_entity").getBody();

        assertThat(entriesOf(body)).isEmpty();
        assertThat(((Number) body.get("total")).longValue()).isZero();
    }

    // === paging parameter clamping ===

    @Test
    void getAuditLogs_sizeAboveTheMaximum_isClampedRatherThanRejected() {
        data.createBrokerageAccountAndGetId();

        var body = api.getForEntity(URL + "?size=100000").getBody();

        assertThat(body.get("size")).isEqualTo(MAX_PAGE_SIZE);
    }

    @Test
    void getAuditLogs_negativePageAndSize_areClampedToValidBounds() {
        data.createBrokerageAccountAndGetId();

        var response = api.getForEntity(URL + "?page=-5&size=-10");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("page")).isEqualTo(0);
        assertThat(((Number) response.getBody().get("size")).intValue()).isPositive();
    }

    @Test
    void getAuditLogs_pageBeyondTheData_returnsEmptyDataButKeepsTheTotal() {
        data.createBrokerageAccountAndGetId();

        var body = api.getForEntity(URL + "?page=500&size=50").getBody();

        assertThat(entriesOf(body)).isEmpty();
        assertThat(((Number) body.get("total")).longValue()).isPositive();
    }

    // === tenant isolation ===

    @Test
    void getAuditLogs_isScopedToTheCallingTenant() {
        var accountId = data.createBrokerageAccountAndGetId();
        // Bootstrapping the second tenant logs it in, and LOGIN is itself audited — so this
        // tenant's trail is legitimately non-empty. What matters is WHOSE rows it contains.
        authHelper.bootstrapSecondTenant(restTemplate);

        var entries = entriesOf(api.getForEntityAs(authHelper.tenant2Token(), URL).getBody());

        assertThat(entries)
                .as("the first tenant's account CREATE must not appear in the second tenant's trail")
                .noneSatisfy(entry -> assertThat(entry.get("entity_id")).isEqualTo(accountId));
        assertThat(entries)
                .as("every visible row belongs to the calling tenant")
                .allSatisfy(entry -> assertThat(entry.get("tenant_id"))
                        .isEqualTo(authHelper.tenant2Id().toString()));
    }

    @Test
    void anonymousRequest_isRejected() {
        var response = api.getAnonForEntity(URL);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    /**
     * The audit log is an administrative surface: it exposes every action taken in the tenant,
     * including other users' activity and admin operations. The web UI only reaches it through the
     * admin area, but the endpoint itself carried no role check and fell through to
     * {@code anyRequest().authenticated()} — so any member or viewer could read the whole trail
     * over HTTP. These two tests pin the role gate so that cannot silently regress to UI-only
     * enforcement again.
     */
    @Test
    void getAuditLogs_asMember_isForbidden() {
        authHelper.createUserDirectly(MEMBER_EMAIL, ROLE_TEST_PASSWORD, "member");
        var token = authHelper.loginAs(restTemplate, MEMBER_EMAIL, ROLE_TEST_PASSWORD);

        var response = api.getForEntityAs(token, URL, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getAuditLogs_asViewer_isForbidden() {
        authHelper.createUserDirectly(VIEWER_EMAIL, ROLE_TEST_PASSWORD, "viewer");
        var token = authHelper.loginAs(restTemplate, VIEWER_EMAIL, ROLE_TEST_PASSWORD);

        var response = api.getForEntityAs(token, URL, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
