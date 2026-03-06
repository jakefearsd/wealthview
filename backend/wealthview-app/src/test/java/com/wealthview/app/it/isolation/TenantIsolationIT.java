package com.wealthview.app.it.isolation;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIsolationIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
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

        var accountBody = Map.of("name", "Tenant 1 Account", "type", "brokerage");
        restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(accountBody, authHelper.adminToken()), MAP_TYPE);

        var scenarioBody = Map.of(
                "name", "Tenant 1 Scenario",
                "retirement_date", "2055-01-01",
                "end_age", 90,
                "inflation_rate", 0.03,
                "birth_year", 1990,
                "withdrawal_rate", 0.04,
                "withdrawal_strategy", "fixed",
                "accounts", List.of(Map.of(
                        "initial_balance", 100000,
                        "annual_contribution", 20000,
                        "expected_return", 0.07,
                        "account_type", "taxable"
                ))
        );
        restTemplate.exchange("/api/v1/projections",
                HttpMethod.POST, authHelper.authEntity(scenarioBody, authHelper.adminToken()), MAP_TYPE);
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
