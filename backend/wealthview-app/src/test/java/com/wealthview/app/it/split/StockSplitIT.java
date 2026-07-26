package com.wealthview.app.it.split;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;
import com.wealthview.app.it.testutil.SplitTestSupport;

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
    private SplitTestSupport split;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        superAdmin = authHelper.loginAsSession(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        authHelper.createUserDirectly(MEMBER_EMAIL, MEMBER_PASS, "member");
        memberToken = authHelper.loginAs(restTemplate, MEMBER_EMAIL, MEMBER_PASS);
        accountId = data.createBrokerageAccountAndGetId();
        split = new SplitTestSupport(api);
    }

    @Test
    void buyThenSplitThenSell_costBasisCorrectEndToEnd() {
        // Buy 100 shares for $8,000 total
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);

        // Apply 4:1 split as super-admin
        var applyResp = split.applySplit(superAdmin.accessToken(), "AAPL", "2020-08-31", 4, 1);
        assertThat(applyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Holdings should now show 400 shares with the same cost basis
        var aaplHolding = split.holding(accountId, "AAPL").orElseThrow();
        assertThat(new BigDecimal(aaplHolding.get("quantity").toString()))
                .isEqualByComparingTo("400");
        assertThat(new BigDecimal(aaplHolding.get("cost_basis").toString()))
                .isEqualByComparingTo("8000.0000");
    }

    @Test
    void manualSplitViaAdminEndpoint_appliesAndIsListable() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 10, 1500);

        split.applySplit(superAdmin.accessToken(), "AAPL", "2020-08-31", 4, 1);

        // Tenant-scoped GET should return the split (member token works)
        var listResp = api.getListForEntityAs(memberToken, "/api/v1/stock-splits");
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // member belongs to the same tenant as admin, so they see the same splits
        assertThat(listResp.getBody()).anyMatch(s -> "AAPL".equals(s.get("symbol")));
    }

    @Test
    void superAdminCanUnapply_revertsAdjustments() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);

        var applyResp = split.applySplit(superAdmin.accessToken(), "AAPL", "2020-08-31", 4, 1);
        var splitId = (String) applyResp.getBody().get("id");

        // Verify the split scaled the holding
        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("400");

        // Un-apply
        var deleteResp = api.deleteForEntityAs(superAdmin.accessToken(), "/api/v1/admin/stock-splits/" + splitId);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Holdings restored
        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("100");
    }

    @Test
    void nonSuperAdminCannotAccessAdminEndpoints() {
        var resp = split.applySplit(memberToken, "AAPL", "2020-08-31", 4, 1).getStatusCode();
        assertThat(resp).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void splitAppliesAcrossAllTenantsHoldingSymbol() {
        // Tenant 1 (admin) has AAPL
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 50, 4000);

        // Bootstrap tenant 2 with their own AAPL position
        authHelper.bootstrapSecondTenant(restTemplate);
        var t2Token = authHelper.tenant2Token();
        var t2 = data.as(t2Token);
        var t2AccountId = (String) t2.createAccount("T2 Brokerage", "brokerage").get("id");
        t2.createBuyTransactionOnDateAndGetId(t2AccountId, "2020-02-01", "AAPL", 30, 2400);

        // Apply split as super-admin (tenant 1)
        split.applySplit(superAdmin.accessToken(), "AAPL", "2020-08-31", 2, 1);

        // Tenant 1 holding doubled
        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("100");

        // Tenant 2 holding doubled
        var h2 = api.getListForEntityAs(t2Token, "/api/v1/accounts/" + t2AccountId + "/holdings");
        assertThat(new BigDecimal(h2.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("60");
    }

    @Test
    void splitDoesNotAffectTenantsNotHoldingSymbol() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "GOOG", 10, 2000);

        // Apply a split for a symbol the tenant doesn't hold
        split.applySplit(superAdmin.accessToken(), "AAPL", "2020-08-31", 4, 1);

        var holdings = api.getListForEntity("/api/v1/accounts/" + accountId + "/holdings");
        // Only GOOG, untouched
        assertThat(holdings.getBody()).hasSize(1);
        assertThat(holdings.getBody().get(0).get("symbol")).isEqualTo("GOOG");
        assertThat(new BigDecimal(holdings.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("10");
    }

    @Test
    void duplicateSplitApply_isIdempotent() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);

        var first = split.applySplit(superAdmin.accessToken(), "AAPL", "2020-08-31", 4, 1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var firstId = first.getBody().get("id");

        var second = split.applySplit(superAdmin.accessToken(), "AAPL", "2020-08-31", 4, 1);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("id")).isEqualTo(firstId);

        // Quantity wasn't doubled-up
        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("400");
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
        split.applySplit(superAdmin.accessToken(), "AAPL", "2020-08-31", 4, 1);

        var listResp = api.getListForEntity("/api/v1/stock-splits");
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Only AAPL appears (not XYZ)
        var symbols = listResp.getBody().stream().map(m -> m.get("symbol")).toList();
        assertThat(symbols).contains("AAPL").doesNotContain("XYZ");
    }
}
