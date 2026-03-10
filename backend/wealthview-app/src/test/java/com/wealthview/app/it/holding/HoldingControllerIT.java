package com.wealthview.app.it.holding;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.LIST_MAP_TYPE;
import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class HoldingControllerIT extends AbstractApiIntegrationTest {

    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        accountId = data.createBrokerageAccountAndGetId();
    }

    @Test
    void list_afterBuyTransactions_showsComputedHoldings() {
        data.createBuyTransaction(accountId, "AAPL", 10, 1500);
        data.createBuyTransaction(accountId, "GOOG", 5, 7000);

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void manualOverride_overridesComputed_returns200() {
        data.createBuyTransaction(accountId, "AAPL", 10, 1500);

        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
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
}
