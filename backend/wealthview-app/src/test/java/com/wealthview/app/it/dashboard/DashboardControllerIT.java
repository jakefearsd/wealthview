package com.wealthview.app.it.dashboard;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @BeforeAll
    void setUpData() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);

        // Create an account with transactions
        var accountBody = Map.of("name", "Dashboard Brokerage", "type", "brokerage");
        var accountResp = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(accountBody, authHelper.adminToken()), MAP_TYPE);
        var accountId = (String) accountResp.getBody().get("id");

        var txBody = Map.of(
                "date", "2024-01-15",
                "type", "buy",
                "symbol", "AAPL",
                "quantity", 10,
                "amount", 1500
        );
        restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
                HttpMethod.POST, authHelper.authEntity(txBody, authHelper.adminToken()), MAP_TYPE);

        // Create a property
        var propertyBody = Map.of(
                "address", "Dashboard Property",
                "purchase_price", 300000,
                "purchase_date", "2020-06-01",
                "current_value", 350000,
                "mortgage_balance", 200000
        );
        restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(propertyBody, authHelper.adminToken()), MAP_TYPE);
    }

    @Test
    void getSummary_withAccountsAndProperties_returnsCorrectNetWorth() {
        var response = restTemplate.exchange("/api/v1/dashboard/summary",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("total_investments", "total_property_equity", "net_worth");
        assertThat(((Number) response.getBody().get("net_worth")).doubleValue()).isGreaterThan(0);
    }

    @Test
    void getSummary_emptyTenant_returnsZeroValues() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);

        var response = restTemplate.exchange("/api/v1/dashboard/summary",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("net_worth")).doubleValue()).isEqualTo(0.0);
    }
}
