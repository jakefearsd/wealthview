package com.wealthview.app.it.holding;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

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

        var response = api.getListForEntity("/api/v1/accounts/" + accountId + "/holdings");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void manualOverride_overridesComputed_returns200() {
        data.createBuyTransaction(accountId, "AAPL", 10, 1500);

        var holdings = api.getListForEntity("/api/v1/accounts/" + accountId + "/holdings");
        var holdingId = (String) holdings.getBody().get(0).get("id");

        var overrideBody = Map.of(
                "account_id", accountId,
                "symbol", "AAPL",
                "quantity", 20,
                "cost_basis", 3000
        );

        var response = api.putForEntity("/api/v1/holdings/" + holdingId, overrideBody);

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

        var response = api.postForEntity("/api/v1/holdings", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("symbol")).isEqualTo("NVDA");
    }
}
