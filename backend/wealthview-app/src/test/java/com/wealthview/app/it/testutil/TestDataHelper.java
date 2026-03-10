package com.wealthview.app.it.testutil;

import com.wealthview.app.it.AuthHelper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

/**
 * Shared helper for creating test entities via the REST API in integration tests.
 * Eliminates duplicated private helper methods across IT classes.
 */
public class TestDataHelper {

    public static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    public static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final TestRestTemplate restTemplate;
    private final AuthHelper authHelper;

    public TestDataHelper(TestRestTemplate restTemplate, AuthHelper authHelper) {
        this.restTemplate = restTemplate;
        this.authHelper = authHelper;
    }

    // ── Accounts ─────────────────────────────────────────────────────────

    public String createAccountAndGetId(String name, String type) {
        var body = Map.of("name", name, "type", type);
        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }

    public void createAccount(String name, String type) {
        var body = Map.of("name", name, "type", type);
        restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
    }

    public String createBrokerageAccountAndGetId() {
        return createAccountAndGetId("Test Brokerage", "brokerage");
    }

    // ── Transactions ─────────────────────────────────────────────────────

    public void createBuyTransaction(String accountId, String symbol, int quantity, int amount) {
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

    public String createBuyTransactionAndGetId(String accountId, String symbol, int quantity, int amount) {
        var body = Map.of(
                "date", "2024-01-15",
                "type", "buy",
                "symbol", symbol,
                "quantity", quantity,
                "amount", amount
        );
        var response = restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }

    public void addTransaction(String accountId, String type, String symbol,
                               String quantity, String amount) {
        var body = Map.of(
                "date", "2025-01-15",
                "type", type,
                "symbol", symbol,
                "quantity", quantity,
                "amount", amount);
        restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTransactions(String accountId) {
        var response = restTemplate.exchange(
                "/api/v1/accounts/" + accountId + "/transactions",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    // ── Holdings ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getHoldings(String accountId) {
        var response = restTemplate.exchange(
                "/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        return response.getBody();
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
        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
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
        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
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

    public void createScenario(String name) {
        restTemplate.exchange("/api/v1/projections",
                HttpMethod.POST, authHelper.authEntity(scenarioBody(name), authHelper.adminToken()), MAP_TYPE);
    }

    public String createScenarioAndGetId(String name) {
        var response = restTemplate.exchange("/api/v1/projections",
                HttpMethod.POST, authHelper.authEntity(scenarioBody(name), authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }

    // ── Spending Profiles ────────────────────────────────────────────────

    public Map<String, Object> spendingProfileBody(String name) {
        return Map.of(
                "name", name,
                "essential_expenses", 40000,
                "discretionary_expenses", 20000,
                "income_streams", List.of(Map.of(
                        "name", "Social Security",
                        "annual_amount", 24000,
                        "start_age", 67
                ))
        );
    }

    public void createSpendingProfile(String name) {
        restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.POST, authHelper.authEntity(spendingProfileBody(name), authHelper.adminToken()), MAP_TYPE);
    }

    public String createSpendingProfileAndGetId(String name) {
        var response = restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.POST, authHelper.authEntity(spendingProfileBody(name), authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }
}
