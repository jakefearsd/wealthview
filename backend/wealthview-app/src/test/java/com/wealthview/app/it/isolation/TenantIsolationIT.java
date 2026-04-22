package com.wealthview.app.it.isolation;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class TenantIsolationIT extends AbstractApiIntegrationTest {

    private String tenant1PropertyId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.bootstrapSecondTenant(restTemplate);

        // Create data for tenant 1
        var propertyBody = Map.of(
                "address", "Tenant 1 Property",
                "purchase_price", 300000,
                "purchase_date", "2020-01-01",
                "current_value", 350000,
                "mortgage_balance", 200000
        );
        var propertyResponse = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(propertyBody, authHelper.adminToken()), MAP_TYPE);
        tenant1PropertyId = (String) propertyResponse.getBody().get("id");

        data.createAccount("Tenant 1 Account", "brokerage");
        data.createScenario("Tenant 1 Scenario");
    }

    @Test
    void tenant2CannotSeeTenant1Properties() {
        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.GET, authHelper.authEntity(authHelper.tenant2Token()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenant2CannotSeeTenant1Accounts() {
        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.GET, authHelper.authEntity(authHelper.tenant2Token()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var content = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(content).isEmpty();
    }

    @Test
    void tenant2CannotSeeTenant1Scenarios() {
        var response = restTemplate.exchange("/api/v1/projections",
                HttpMethod.GET, authHelper.authEntity(authHelper.tenant2Token()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenant2CannotDeleteTenant1Expense_evenWithOwnPropertyIdInPath() {
        // Tenant 1 creates an expense on their own property
        var expenseBody = Map.of(
                "date", "2025-03-01",
                "amount", 500,
                "category", "maintenance",
                "description", "Tenant 1 plumbing fix"
        );
        var createExpense = restTemplate.exchange(
                "/api/v1/properties/" + tenant1PropertyId + "/expenses",
                HttpMethod.POST, authHelper.authEntity(expenseBody, authHelper.adminToken()), MAP_TYPE);
        assertThat(createExpense.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Grab the expense ID via list endpoint (only tenant 1 can see it)
        var listResponse = restTemplate.exchange(
                "/api/v1/properties/" + tenant1PropertyId + "/expenses",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(listResponse.getBody()).hasSize(1);
        var tenant1ExpenseId = (String) listResponse.getBody().get(0).get("id");

        // Tenant 2 creates their own property (required to pass the property tenant check)
        var tenant2PropertyBody = Map.of(
                "address", "Tenant 2 Property",
                "purchase_price", 250000,
                "purchase_date", "2021-05-01",
                "current_value", 275000,
                "mortgage_balance", 180000
        );
        var tenant2PropResp = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(tenant2PropertyBody, authHelper.tenant2Token()), MAP_TYPE);
        assertThat(tenant2PropResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var tenant2PropertyId = (String) tenant2PropResp.getBody().get("id");

        // Tenant 2 attempts cross-tenant delete via their own property ID in the path
        // and tenant 1's expense ID. Current buggy behaviour: 204 (expense deleted).
        // Expected secure behaviour: 404 (expense not found for this tenant).
        var attack = restTemplate.exchange(
                "/api/v1/properties/" + tenant2PropertyId + "/expenses/" + tenant1ExpenseId,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.tenant2Token()), Void.class);

        assertThat(attack.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Tenant 1's expense must still exist
        var afterAttackList = restTemplate.exchange(
                "/api/v1/properties/" + tenant1PropertyId + "/expenses",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(afterAttackList.getBody()).hasSize(1);
    }
}
