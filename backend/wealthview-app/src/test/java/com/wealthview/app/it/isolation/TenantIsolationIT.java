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
        restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(propertyBody, authHelper.adminToken()), MAP_TYPE);

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
}
