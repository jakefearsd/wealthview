package com.wealthview.app.it.importjob;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportPositionsControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @SuppressWarnings("unchecked")
    private static final ParameterizedTypeReference<Map<String, Object>> PAGE_TYPE =
            new ParameterizedTypeReference<>() {};

    private String accountId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
        accountId = createAccount();
    }

    @Test
    @SuppressWarnings("unchecked")
    void importPositions_clearsExistingAndImportsNew() {
        // Add a buy transaction first
        addTransaction(accountId, "buy", "MSFT", "10", "3500.00");

        // Verify the transaction exists
        var txnsBefore = getTransactions(accountId);
        assertThat(txnsBefore).hasSize(1);

        // Import positions (should clear existing)
        var importResponse = uploadPositions(accountId);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(importResponse.getBody().get("source")).isEqualTo("positions");

        var successfulRows = ((Number) importResponse.getBody().get("successful_rows")).intValue();
        assertThat(successfulRows).isEqualTo(6); // FCASH deposit + 5 opening_balance

        // Verify old transaction is gone
        var txnsAfter = getTransactions(accountId);
        assertThat(txnsAfter).hasSize(6);

        // Verify FCASH created as deposit
        var deposit = txnsAfter.stream()
                .filter(t -> "deposit".equals(t.get("type")))
                .findFirst().orElseThrow();
        assertThat(((Number) deposit.get("amount")).doubleValue()).isCloseTo(704.82, org.assertj.core.data.Offset.offset(0.01));

        // Verify AMZN opening_balance
        var amzn = txnsAfter.stream()
                .filter(t -> "AMZN".equals(t.get("symbol")))
                .findFirst().orElseThrow();
        assertThat(amzn.get("type")).isEqualTo("opening_balance");
        assertThat(((Number) amzn.get("quantity")).doubleValue()).isCloseTo(1100.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importPositions_createsHoldingsWithMoneyMarketFlag() {
        uploadPositions(accountId);

        // Verify holdings
        var holdings = getHoldings(accountId);
        assertThat(holdings).hasSize(5); // AMZN, NVDA, SCHD, SPAXX, VOO (FCASH is deposit, no holding)

        // Verify SPAXX is flagged as money market
        var spaxx = holdings.stream()
                .filter(h -> "SPAXX".equals(h.get("symbol")))
                .findFirst().orElseThrow();
        assertThat(spaxx.get("is_money_market")).isEqualTo(true);
        assertThat(((Number) spaxx.get("quantity")).doubleValue()).isCloseTo(196049.86, org.assertj.core.data.Offset.offset(0.01));

        // Verify VOO is not money market
        var voo = holdings.stream()
                .filter(h -> "VOO".equals(h.get("symbol")))
                .findFirst().orElseThrow();
        assertThat(voo.get("is_money_market")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importPositions_theoreticalHistory_includesSpaxx() {
        uploadPositions(accountId);

        // Get theoretical history — SPAXX should appear at constant value
        var historyResponse = restTemplate.exchange(
                "/api/v1/accounts/" + accountId + "/theoretical-history?years=1",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = historyResponse.getBody();
        assertThat(body.get("has_money_market_holdings")).isEqualTo(true);

        var symbols = (List<String>) body.get("symbols");
        assertThat(symbols).contains("SPAXX");
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> uploadPositions(String acctId) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ClassPathResource("testdata/Portfolio_Positions_Mar-05-2026.csv"));
        body.add("accountId", acctId);
        body.add("format", "fidelityPositions");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(authHelper.adminToken());

        return restTemplate.exchange("/api/v1/import/positions",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);
    }

    private void addTransaction(String acctId, String type, String symbol,
                                String quantity, String amount) {
        var body = Map.of(
                "date", "2025-01-15",
                "type", type,
                "symbol", symbol,
                "quantity", quantity,
                "amount", amount);
        restTemplate.exchange("/api/v1/accounts/" + acctId + "/transactions",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getTransactions(String acctId) {
        var response = restTemplate.exchange(
                "/api/v1/accounts/" + acctId + "/transactions",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getHoldings(String acctId) {
        var response = restTemplate.exchange(
                "/api/v1/accounts/" + acctId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        return response.getBody();
    }

    private String createAccount() {
        var body = Map.of("name", "Positions Test Account", "type", "brokerage");
        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }
}
