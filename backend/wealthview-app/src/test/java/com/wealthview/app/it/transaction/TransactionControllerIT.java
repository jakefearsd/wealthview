package com.wealthview.app.it.transaction;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionControllerIT extends AbstractApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        var response = api.postForEntity("/api/v1/accounts/" + accountId + "/transactions", body);

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

        var response = api.postForEntity("/api/v1/accounts/" + accountId + "/transactions", sellBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("type")).isEqualTo("sell");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_returnsNewestTransactionDateFirst() {
        for (var date : java.util.List.of("2024-03-01", "2024-01-01", "2024-02-01")) {
            api.postForEntity("/api/v1/accounts/" + accountId + "/transactions",
                    Map.of("date", date, "type", "buy", "symbol", "AAPL", "quantity", 1, "amount", 100));
        }

        var response = api.getForEntity("/api/v1/accounts/" + accountId + "/transactions");

        var content = (java.util.List<Map<String, Object>>) response.getBody().get("data");
        assertThat(content).extracting(t -> t.get("date"))
                .containsExactly("2024-03-01", "2024-02-01", "2024-01-01");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_returnsTransactionsForAccount() {
        data.createBuyTransaction(accountId, "AAPL", 10, 1500);
        data.createBuyTransaction(accountId, "GOOG", 5, 7000);

        var response = api.getForEntity("/api/v1/accounts/" + accountId + "/transactions");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var content = (java.util.List<Map<String, Object>>) response.getBody().get("data");
        assertThat(content).hasSize(2);
    }

    @Test
    void update_existingTransaction_returns200() {
        var txId = (String) data.createBuyTransaction(accountId, "AAPL", 10, 1500).get("id");
        var updateBody = Map.of(
                "date", "2024-01-20",
                "type", "buy",
                "symbol", "AAPL",
                "quantity", 15,
                "amount", 2250
        );

        var response = api.putForEntity("/api/v1/transactions/" + txId, updateBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void delete_existingTransaction_returns204() {
        var txId = (String) data.createBuyTransaction(accountId, "MSFT", 8, 2400).get("id");

        var response = api.deleteForEntity("/api/v1/transactions/" + txId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listBySymbol_legacyRowStoredLowerCase_isFoundByItsStoredSymbol() {
        // Rows written before symbols were normalised on write keep their spelling (no migration).
        jdbcTemplate.update("""
                INSERT INTO transactions (account_id, tenant_id, date, type, symbol, quantity, amount)
                SELECT id, tenant_id, DATE '2020-03-01', 'buy', 'vti', 2, 300 FROM accounts WHERE id = ?::uuid
                """, accountId);

        var response = api.getForEntity("/api/v1/accounts/" + accountId + "/transactions?symbol=vti");

        var content = (java.util.List<Map<String, Object>>) response.getBody().get("data");
        assertThat(content).extracting(t -> t.get("symbol")).containsExactly("vti");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listBySymbol_looselyTypedQuery_findsNormalisedRows() {
        data.createBuyTransaction(accountId, " aapl ", 1, 100);

        var response = api.getForEntity("/api/v1/accounts/" + accountId + "/transactions?symbol=aapl");

        var content = (java.util.List<Map<String, Object>>) response.getBody().get("data");
        assertThat(content).extracting(t -> t.get("symbol")).containsExactly("AAPL");
    }

    @Test
    void create_importedStyleRows_negativeAmountAndSellWithoutQuantity_return201() {
        var withdrawal = api.postForEntity("/api/v1/accounts/" + accountId + "/transactions",
                Map.of("date", "2024-01-05", "type", "withdrawal", "amount", -250));
        var sell = api.postForEntity("/api/v1/accounts/" + accountId + "/transactions",
                Map.of("date", "2024-01-06", "type", "sell", "symbol", "AAPL", "amount", 300));

        assertThat(withdrawal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sell.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
