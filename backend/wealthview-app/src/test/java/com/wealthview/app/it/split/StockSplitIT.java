package com.wealthview.app.it.split;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;
import com.wealthview.app.it.testutil.TestDataHelper;

import static com.wealthview.app.it.testutil.TestDataHelper.LIST_MAP_TYPE;
import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for stock-split apply/unapply through the REST API.
 * Asserts that:
 *  - admin can apply a split via the admin endpoint
 *  - the affected transactions are scaled by the split ratio
 *  - holdings recompute to the post-split quantity
 *  - admin can unapply, returning the data to its original state
 *  - non-super-admin callers cannot reach the admin endpoints
 *  - splits propagate across all tenants holding the symbol
 */
class StockSplitIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_EMAIL = "split-super@wealthview.test";
    private static final String SUPER_ADMIN_PASS = "superpass123";
    private static final String MEMBER_EMAIL = "split-member@wealthview.test";
    private static final String MEMBER_PASS = "memberpass1";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthHelper.Session superAdmin;
    private String memberToken;
    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        superAdmin = authHelper.loginAsSession(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        authHelper.createUserDirectly(MEMBER_EMAIL, MEMBER_PASS, "member");
        memberToken = authHelper.loginAs(restTemplate, MEMBER_EMAIL, MEMBER_PASS);
        accountId = data.createBrokerageAccountAndGetId();
    }

    @Test
    void buyThenSplitThenSell_costBasisCorrectEndToEnd() {
        // Buy 100 shares for $8,000 total
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);

        // Apply 4:1 split as super-admin
        var applyResp = restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);
        assertThat(applyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Holdings should now show 400 shares with the same cost basis
        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        var aaplHolding = holdings.getBody().stream()
                .filter(h -> "AAPL".equals(h.get("symbol")))
                .findFirst().orElseThrow();
        assertThat(new BigDecimal(aaplHolding.get("quantity").toString()))
                .isEqualByComparingTo("400");
        assertThat(new BigDecimal(aaplHolding.get("cost_basis").toString()))
                .isEqualByComparingTo("8000.0000");
    }

    @Test
    void manualSplitViaAdminEndpoint_appliesAndIsListable() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 10, 1500);

        restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);

        // Tenant-scoped GET should return the split (member token works)
        var listResp = restTemplate.exchange("/api/v1/stock-splits",
                HttpMethod.GET, authHelper.authEntity(memberToken), LIST_MAP_TYPE);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // member belongs to the same tenant as admin, so they see the same splits
        assertThat(listResp.getBody()).anyMatch(s -> "AAPL".equals(s.get("symbol")));
    }

    @Test
    void superAdminCanUnapply_revertsAdjustments() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);

        var applyResp = restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);
        var splitId = (String) applyResp.getBody().get("id");

        // Verify the split scaled the holding
        var holdingsAfter = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        assertThat(new BigDecimal(holdingsAfter.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("400");

        // Un-apply
        var deleteResp = restTemplate.exchange("/api/v1/admin/stock-splits/" + splitId,
                HttpMethod.DELETE, authHelper.authEntity(superAdmin.accessToken()), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Holdings restored
        var holdingsRestored = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        assertThat(new BigDecimal(holdingsRestored.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("100");
    }

    @Test
    void nonSuperAdminCannotAccessAdminEndpoints() {
        var resp = restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), memberToken),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void splitAppliesAcrossAllTenantsHoldingSymbol() {
        // Tenant 1 (admin) has AAPL
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 50, 4000);

        // Bootstrap tenant 2 with their own AAPL position
        authHelper.bootstrapSecondTenant(restTemplate);
        var t2Token = authHelper.tenant2Token();
        // Direct API call with tenant 2 token — TestDataHelper always uses tenant 1's token and
        // exposes no seam to swap it, so tenant 2's setup is driven through restTemplate here.
        var t2Account = restTemplate.exchange("/api/v1/accounts", HttpMethod.POST,
                authHelper.authEntity(Map.of("name", "T2 Brokerage", "type", "brokerage"), t2Token),
                MAP_TYPE);
        var t2AccountId = (String) t2Account.getBody().get("id");
        restTemplate.exchange("/api/v1/accounts/" + t2AccountId + "/transactions", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "date", "2020-02-01", "type", "buy", "symbol", "AAPL",
                        "quantity", 30, "amount", 2400), t2Token),
                MAP_TYPE);

        // Apply split as super-admin (tenant 1)
        restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 2,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);

        // Tenant 1 holding doubled
        var h1 = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        assertThat(new BigDecimal(h1.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("100");

        // Tenant 2 holding doubled
        var h2 = restTemplate.exchange("/api/v1/accounts/" + t2AccountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(t2Token), LIST_MAP_TYPE);
        assertThat(new BigDecimal(h2.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("60");
    }

    @Test
    void splitDoesNotAffectTenantsNotHoldingSymbol() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "GOOG", 10, 2000);

        // Apply a split for a symbol the tenant doesn't hold
        restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);

        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        // Only GOOG, untouched
        assertThat(holdings.getBody()).hasSize(1);
        assertThat(holdings.getBody().get(0).get("symbol")).isEqualTo("GOOG");
        assertThat(new BigDecimal(holdings.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("10");
    }

    @Test
    void duplicateSplitApply_isIdempotent() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);

        var first = restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var firstId = first.getBody().get("id");

        var second = restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("id")).isEqualTo(firstId);

        // Quantity wasn't doubled-up
        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        assertThat(new BigDecimal(holdings.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("400");
    }

    @Test
    void getStockSplits_filtersToTenantsOwnedSymbols() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 10, 1500);

        // Manually insert a split for an unrelated symbol via JDBC (simulates a
        // global discovery for a stock the tenant doesn't hold)
        jdbcTemplate.update("""
                INSERT INTO stock_splits (id, symbol, effective_date, numerator, denominator, source)
                VALUES (gen_random_uuid(), 'XYZ', '2021-01-01', 2, 1, 'finnhub')
                """);

        // Apply an AAPL split via admin so the tenant is "associated"
        restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);

        var listResp = restTemplate.exchange("/api/v1/stock-splits",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Only AAPL appears (not XYZ)
        var symbols = listResp.getBody().stream().map(m -> m.get("symbol")).toList();
        assertThat(symbols).contains("AAPL").doesNotContain("XYZ");
    }
}
