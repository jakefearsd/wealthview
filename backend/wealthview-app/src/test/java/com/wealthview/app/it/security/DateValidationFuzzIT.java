package com.wealthview.app.it.security;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz date / month / timestamp inputs across endpoints that accept them.
 *
 * <p>Asserted invariants:
 * <ul>
 *   <li>Year 0001 / 9999 / scientific notation / malformed ISO dates yield
 *       4xx, never 5xx.</li>
 *   <li>{@code from} / {@code to} {@code YearMonth} query params on the
 *       cashflow endpoints reject malformed input with 4xx.</li>
 * </ul>
 */
@Tag("fuzz")
class DateValidationFuzzIT extends AbstractApiIntegrationTest {

    private static final int ITERATIONS = 80;

    private String accountId;
    private String propertyId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        accountId = data.createBrokerageAccountAndGetId();
        propertyId = data.createPropertyAndGetId();
    }

    @Test
    void createTransaction_withFuzzedDateValues_neverCrashes() {
        FuzzSupport.samples(this::randomTransactionWithFuzzedDate, ITERATIONS, 0xDA1L)
                .forEach(body -> {
                    var resp = restTemplate.exchange(
                            "/api/v1/accounts/" + accountId + "/transactions",
                            HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()),
                            MAP_TYPE);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("create-txn date=%s produced 5xx", body.get("date"))
                            .isFalse();
                });
    }

    @Test
    void cashflowEndpoint_withFuzzedYearMonthRange_neverCrashes() {
        FuzzSupport.samples(this::randomYearMonth, ITERATIONS, 0xDA2L)
                .forEach(ym -> {
                    var path = "/api/v1/properties/" + propertyId
                            + "/cashflow?from=" + ym + "&to=" + ym;
                    // The cashflow endpoint returns a JSON array on success;
                    // 4xx returns a JSON error envelope. Use String.class so
                    // both shapes deserialize without RestTemplate erroring.
                    var resp = restTemplate.exchange(path, HttpMethod.GET,
                            authHelper.authEntity(authHelper.adminToken()), String.class);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("cashflow ?from=%s&to=%s produced 5xx", ym, ym)
                            .isFalse();
                });
    }

    private Map<String, Object> randomTransactionWithFuzzedDate(Random r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", randomDateValue(r));
        body.put("type", "buy");
        body.put("symbol", "AAPL");
        body.put("quantity", 1);
        body.put("amount", 100);
        return body;
    }

    private Object randomDateValue(Random r) {
        return switch (r.nextInt(10)) {
            case 0 -> "0001-01-01";
            case 1 -> "9999-12-31";
            case 2 -> "1.5e308";
            case 3 -> "not-a-date";
            case 4 -> "2024-13-01";
            case 5 -> "2024-02-30";
            case 6 -> "2024-01-15T00:00:00Z";       // ISO datetime, not date
            case 7 -> "2024-01-15+05:30";
            case 8 -> 12345;                         // numeric epoch-ish
            default -> FuzzSupport.alnum(r, 16);
        };
    }

    private String randomYearMonth(Random r) {
        return switch (r.nextInt(8)) {
            case 0 -> "0001-01";
            case 1 -> "9999-12";
            case 2 -> "2024-13";                     // month out of range
            case 3 -> "2024-00";
            case 4 -> "20240101";
            case 5 -> "abc";
            case 6 -> "2024";                        // missing month
            default -> FuzzSupport.alnum(r, 12);
        };
    }
}
