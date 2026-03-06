package com.wealthview.app.it.holding;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private String accountId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
        accountId = createAccount();
    }

    @Test
    void list_afterBuyTransactions_showsComputedHoldings() {
        createBuyTransaction("AAPL", 10, 1500);
        createBuyTransaction("GOOG", 5, 7000);

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void manualOverride_overridesComputed_returns200() {
        createBuyTransaction("AAPL", 10, 1500);

        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        var holdingId = (String) holdings.getBody().get(0).get("id");

        var overrideBody = Map.of(
                "account_id", accountId,
                "symbol", "AAPL",
                "quantity", 20,
                "cost_basis", 3000
        );

        var response = restTemplate.exchange("/api/v1/holdings/" + holdingId,
                HttpMethod.PUT, authHelper.authEntity(overrideBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createManual_returnsCreatedHolding() {
        var body = Map.of(
                "account_id", accountId,
                "symbol", "NVDA",
                "quantity", 25,
                "cost_basis", 5000
        );

        var response = restTemplate.exchange("/api/v1/holdings",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("symbol")).isEqualTo("NVDA");
    }

    private String createAccount() {
        var body = Map.of("name", "Test Brokerage", "type", "brokerage");
        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }

    private void createBuyTransaction(String symbol, int quantity, int amount) {
        var body = Map.of(
                "date", "2024-01-15",
                "type", "buy",
                "symbol", symbol,
                "quantity", quantity,
                "amount", amount
        );
        restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
    }
}
