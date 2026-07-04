package com.wealthview.app.it.testutil;

import java.util.List;
import java.util.Map;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;

import com.wealthview.app.it.AuthHelper;

/**
 * Shared helper for creating test entities via the REST API in integration tests.
 * Eliminates duplicated private helper methods across IT classes. All calls go
 * through {@link ApiClient} as the bootstrapped admin; creator methods return the
 * response body map so callers can pull whatever field they need (usually "id")
 * or ignore the result entirely.
 */
public class TestDataHelper {

    public static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = ApiClient.MAP_TYPE;

    public static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            ApiClient.LIST_MAP_TYPE;

    private final ApiClient api;

    public TestDataHelper(TestRestTemplate restTemplate, AuthHelper authHelper) {
        this.api = new ApiClient(restTemplate, authHelper);
    }

    // ── Accounts ─────────────────────────────────────────────────────────

    public Map<String, Object> createAccount(String name, String type) {
        return api.post("/api/v1/accounts", Map.of("name", name, "type", type));
    }

    public String createBrokerageAccountAndGetId() {
        return (String) createAccount("Test Brokerage", "brokerage").get("id");
    }

    // ── Transactions ─────────────────────────────────────────────────────

    public Map<String, Object> createBuyTransaction(String accountId, String symbol, int quantity, int amount) {
        var body = Map.of(
                "date", "2024-01-15",
                "type", "buy",
                "symbol", symbol,
                "quantity", quantity,
                "amount", amount
        );
        return api.post("/api/v1/accounts/" + accountId + "/transactions", body);
    }

    public void addTransaction(String accountId, String type, String symbol,
                               String quantity, String amount) {
        var body = Map.of(
                "date", "2025-01-15",
                "type", type,
                "symbol", symbol,
                "quantity", quantity,
                "amount", amount);
        api.post("/api/v1/accounts/" + accountId + "/transactions", body);
    }

    public String createBuyTransactionOnDateAndGetId(String accountId, String date, String symbol,
                                                     int quantity, int amount) {
        var body = Map.of(
                "date", date,
                "type", "buy",
                "symbol", symbol,
                "quantity", quantity,
                "amount", amount);
        return (String) api.post("/api/v1/accounts/" + accountId + "/transactions", body).get("id");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTransactions(String accountId) {
        var response = api.get("/api/v1/accounts/" + accountId + "/transactions");
        return (List<Map<String, Object>>) response.get("data");
    }

    // ── Holdings ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> getHoldings(String accountId) {
        return api.getList("/api/v1/accounts/" + accountId + "/holdings");
    }

    // ── Properties ───────────────────────────────────────────────────────

    public String createPropertyAndGetId() {
        var body = Map.of(
                "address", "123 Main St",
                "purchase_price", 300000,
                "purchase_date", "2020-06-01",
                "current_value", 350000,
                "mortgage_balance", 200000
        );
        return (String) api.post("/api/v1/properties", body).get("id");
    }

    public String createPropertyWithLoanAndGetId() {
        var body = Map.of(
                "address", "456 Oak Ave",
                "purchase_price", 400000,
                "purchase_date", "2020-01-01",
                "current_value", 450000,
                "loan_amount", 320000,
                "annual_interest_rate", 0.065,
                "loan_term_months", 360,
                "loan_start_date", "2020-01-01",
                "use_computed_balance", false
        );
        return (String) api.post("/api/v1/properties", body).get("id");
    }

    // ── Scenarios ────────────────────────────────────────────────────────

    public Map<String, Object> scenarioBody(String name) {
        return Map.of(
                "name", name,
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
    }

    public Map<String, Object> createScenario(String name) {
        return api.post("/api/v1/projections", scenarioBody(name));
    }

    // ── Exchange Rates ──────────────────────────────────────────────────

    public void createExchangeRate(String currencyCode, double rateToUsd) {
        api.post("/api/v1/exchange-rates", Map.of("currency_code", currencyCode, "rate_to_usd", rateToUsd));
    }

    public String createAccountWithCurrencyAndGetId(String name, String type, String currency) {
        var body = Map.of("name", name, "type", type, "currency", currency);
        return (String) api.post("/api/v1/accounts", body).get("id");
    }

    // ── Spending Profiles ────────────────────────────────────────────────

    public Map<String, Object> spendingProfileBody(String name) {
        return Map.of(
                "name", name,
                "essential_expenses", 40000,
                "discretionary_expenses", 20000
        );
    }

    public Map<String, Object> createSpendingProfile(String name) {
        return api.post("/api/v1/spending-profiles", spendingProfileBody(name));
    }
}
