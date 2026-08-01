package com.wealthview.app.it.security;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for {@code PUT /api/v1/securities/{symbol}/classification}, which had no
 * integration test.
 *
 * <p>The classification override decides which asset-class return series a holding is projected
 * with (realism-v2 allocation-driven returns), so a wrong or cross-tenant override silently
 * changes projected growth rather than failing. The controller maps the incoming string through
 * {@code AssetClass.fromKey} after lower-casing it and re-wraps the failure, so the accepted
 * vocabulary is only observable here.
 */
class SecurityClassificationControllerIT extends AbstractApiIntegrationTest {

    private static final String SYMBOL = "ZZTEST";

    private static String url(String symbol) {
        return "/api/v1/securities/" + symbol + "/classification";
    }

    private static Map<String, Object> body(Object assetClass) {
        var map = new HashMap<String, Object>();
        map.put("asset_class", assetClass);
        return map;
    }

    @Test
    void setClassification_knownAssetClass_returns200EchoingSymbolAndClass() {
        var response = api.putForEntity(url(SYMBOL), body("intl_stock"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("symbol")).isEqualTo(SYMBOL);
        assertThat(response.getBody().get("asset_class")).isEqualTo("intl_stock");
    }

    @Test
    void setClassification_uppercaseAssetClass_isAcceptedAndNormalised() {
        var response = api.putForEntity(url(SYMBOL), body("BOND"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("asset_class"))
                .as("the controller lower-cases before resolving the key")
                .isEqualTo("bond");
    }

    @Test
    void setClassification_appliedTwice_lastWriteWins() {
        api.putForEntity(url(SYMBOL), body("us_stock"));

        var response = api.putForEntity(url(SYMBOL), body("cash"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("asset_class")).isEqualTo("cash");
    }

    @Test
    void setClassification_unknownAssetClass_returns400() {
        var response = api.putForEntity(url(SYMBOL), body("crypto"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void setClassification_blankAssetClass_returns400() {
        assertThat(api.putForEntity(url(SYMBOL), body("  ")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void setClassification_missingAssetClass_returns400() {
        assertThat(api.putForEntity(url(SYMBOL), Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void setClassification_twoTenantsOnTheSameSymbol_areIndependentOverrides() {
        api.putForEntity(url(SYMBOL), body("us_stock"));
        authHelper.bootstrapSecondTenant(restTemplate);

        var otherTenant = api.putForEntityAs(authHelper.tenant2Token(), url(SYMBOL), body("bond"));

        assertThat(otherTenant.getStatusCode())
                .as("overrides are tenant-scoped, so a second tenant classifying the same symbol "
                        + "must not collide with the first")
                .isEqualTo(HttpStatus.OK);
        assertThat(otherTenant.getBody().get("asset_class")).isEqualTo("bond");
    }

    @Test
    void anonymousRequest_isRejected() {
        var response = api.putAnonForEntity(url(SYMBOL), body("bond"), Map.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
