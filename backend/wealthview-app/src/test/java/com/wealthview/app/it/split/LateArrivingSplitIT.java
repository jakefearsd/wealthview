package com.wealthview.app.it.split;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for late-arriving pre-split transactions: a split is
 * applied first, then a transaction dated before it is created. The stored
 * quantity must be split-adjusted at insert time, and unapply must restore it.
 */
class LateArrivingSplitIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_EMAIL = "late-super@wealthview.test";
    private static final String SUPER_ADMIN_PASS = "superpass123";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthHelper.Session superAdmin;
    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        superAdmin = authHelper.loginAsSession(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        accountId = data.createBrokerageAccountAndGetId();
    }

    @Test
    void transactionImportedAfterSplit_isAdjustedThenRestoredOnUnapply() {
        // Split applied FIRST (no transactions exist yet for AAPL).
        var applyResp = restTemplate.exchange("/api/v1/admin/stock-splits", HttpMethod.POST,
                authHelper.authEntity(Map.of(
                        "symbol", "AAPL",
                        "effective_date", "2020-08-31",
                        "numerator", 4,
                        "denominator", 1), superAdmin.accessToken()),
                MAP_TYPE);
        var splitId = (String) applyResp.getBody().get("id");

        // Transaction dated BEFORE the split arrives afterward (posts through
        // TransactionService.create -> SplitAdjustmentApplier).
        var txnId = data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);

        var adjustedQty = jdbcTemplate.queryForObject(
                "select quantity from transactions where id = ?::uuid", BigDecimal.class, txnId);
        assertThat(adjustedQty).isEqualByComparingTo("400.0000");

        var adjustmentRows = jdbcTemplate.queryForObject(
                "select count(*) from stock_split_adjustments where target_row_id = ?::uuid",
                Integer.class, txnId);
        assertThat(adjustmentRows).isEqualTo(1);

        // Unapply restores the raw quantity.
        restTemplate.exchange("/api/v1/admin/stock-splits/" + splitId, HttpMethod.DELETE,
                authHelper.authEntity(superAdmin.accessToken()), MAP_TYPE);

        var restoredQty = jdbcTemplate.queryForObject(
                "select quantity from transactions where id = ?::uuid", BigDecimal.class, txnId);
        assertThat(restoredQty).isEqualByComparingTo("100.0000");
    }
}
