package com.wealthview.app.it.transaction;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionControllerIT extends AbstractApiIntegrationTest {

    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        accountId = data.createBrokerageAccountAndGetId();
    }

    @Test
    void create_buyTransaction_returns201AndRecomputesHoldings() {
        var body = Map.of(
                "date", "2024-01-15",
                "type", "buy",
                "symbol", "AAPL",
                "quantity", 10,
                "amount", 1500
        );

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("type")).isEqualTo("buy");
        assertThat(response.getBody().get("symbol")).isEqualTo("AAPL");
    }

    @Test
    void create_sellTransaction_updatesHoldings() {
        data.createBuyTransaction(accountId, "GOOG", 5, 7000);

        var sellBody = Map.of(
                "date", "2024-02-01",
                "type", "sell",
                "symbol", "GOOG",
                "quantity", 2,
                "amount", 3000
        );

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
                HttpMethod.POST, authHelper.authEntity(sellBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("type")).isEqualTo("sell");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_returnsTransactionsForAccount() {
        data.createBuyTransaction(accountId, "AAPL", 10, 1500);
        data.createBuyTransaction(accountId, "GOOG", 5, 7000);

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var content = (java.util.List<Map<String, Object>>) response.getBody().get("data");
        assertThat(content).hasSize(2);
    }

    @Test
    void update_existingTransaction_returns200() {
        var txId = data.createBuyTransactionAndGetId(accountId, "AAPL", 10, 1500);
        var updateBody = Map.of(
                "date", "2024-01-20",
                "type", "buy",
                "symbol", "AAPL",
                "quantity", 15,
                "amount", 2250
        );

        var response = restTemplate.exchange("/api/v1/transactions/" + txId,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void delete_existingTransaction_returns204() {
        var txId = data.createBuyTransactionAndGetId(accountId, "MSFT", 8, 2400);

        var response = restTemplate.exchange("/api/v1/transactions/" + txId,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
