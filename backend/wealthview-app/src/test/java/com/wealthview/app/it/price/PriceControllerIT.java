package com.wealthview.app.it.price;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for {@code /api/v1/prices}, which had no integration test.
 *
 * <p>Prices are deliberately GLOBAL, not tenant-scoped: {@code PriceController} takes no
 * {@code TenantUserPrincipal} and {@code PriceService} takes no tenant id, because a security's
 * closing price is the same fact for everyone. That is an unusual shape in this codebase — every
 * other domain endpoint is tenant-filtered — so
 * {@link #getLatest_isSharedAcrossTenantsBecausePricesAreGlobal()} pins it explicitly rather than
 * leaving a future reader to wonder whether the missing filter is a bug.
 *
 * <p>A test-only symbol is used throughout so the assertions do not collide with
 * {@code R__seed_stock_prices.sql}, whose rows are not tenant data and so survive
 * {@code DatabaseCleaner}.
 */
class PriceControllerIT extends AbstractApiIntegrationTest {

    private static final String URL = "/api/v1/prices";
    private static final String SYMBOL = "ZZTEST";

    private static Map<String, Object> priceBody(String symbol, String date, Object close) {
        var body = new HashMap<String, Object>();
        body.put("symbol", symbol);
        body.put("date", date);
        body.put("close_price", close);
        return body;
    }

    // === create ===

    @Test
    void create_validPrice_returns201WithTheStoredValues() {
        var response = api.postForEntity(URL, priceBody(SYMBOL, "2025-06-02", 123.45));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("symbol")).isEqualTo(SYMBOL);
        assertThat(response.getBody().get("date")).isEqualTo("2025-06-02");
        assertThat(((Number) response.getBody().get("close_price")).doubleValue()).isEqualTo(123.45);
    }

    @Test
    void create_blankSymbol_returns400() {
        var response = api.postForEntity(URL, priceBody("  ", "2025-06-02", 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_missingClosePrice_returns400() {
        var body = new HashMap<String, Object>();
        body.put("symbol", SYMBOL);
        body.put("date", "2025-06-02");

        assertThat(api.postForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_malformedDate_returns400() {
        var response = api.postForEntity(URL, priceBody(SYMBOL, "02/06/2025", 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // === latest ===

    @Test
    void getLatest_afterSeveralDates_returnsTheMostRecentClose() {
        api.postForEntity(URL, priceBody(SYMBOL, "2025-06-01", 100.00));
        api.postForEntity(URL, priceBody(SYMBOL, "2025-06-03", 130.00));
        api.postForEntity(URL, priceBody(SYMBOL, "2025-06-02", 110.00));

        var response = api.getForEntity(URL + "/" + SYMBOL + "/latest");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("date")).isEqualTo("2025-06-03");
        assertThat(((Number) response.getBody().get("close_price")).doubleValue()).isEqualTo(130.00);
    }

    @Test
    void getLatest_unknownSymbol_returns404() {
        var response = api.getForEntity(URL + "/NOSUCHSYMBOL/latest");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getLatest_isSharedAcrossTenantsBecausePricesAreGlobal() {
        api.postForEntity(URL, priceBody(SYMBOL, "2025-06-02", 123.45));
        authHelper.bootstrapSecondTenant(restTemplate);

        var response = api.getForEntityAs(authHelper.tenant2Token(), URL + "/" + SYMBOL + "/latest");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("close_price")).doubleValue()).isEqualTo(123.45);
    }

    // === list ===

    @Test
    void list_includesACreatedSymbolAtItsLatestPrice() {
        api.postForEntity(URL, priceBody(SYMBOL, "2025-06-01", 100.00));
        api.postForEntity(URL, priceBody(SYMBOL, "2025-06-03", 130.00));

        var response = api.getListForEntity(URL);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .filteredOn(row -> SYMBOL.equals(row.get("symbol")))
                .singleElement()
                .satisfies(row -> assertThat(((Number) row.get("close_price")).doubleValue())
                        .as("the list is one row per symbol, at its latest date")
                        .isEqualTo(130.00));
    }

    @Test
    void anonymousRequest_isRejected() {
        var response = api.getAnonForEntity(URL);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
