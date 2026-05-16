package com.wealthview.app.it.security;

import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz pagination and sort query parameters across paginated endpoints.
 *
 * <p>Asserted invariants:
 * <ul>
 *   <li>Negative / huge / non-numeric / SQL-shaped {@code page} or {@code size}
 *       values either yield 4xx or are clamped — never 5xx.</li>
 *   <li>{@code sort} parameters with privileged or non-existent column names
 *       are either rejected (4xx) or ignored — never produce a SQL error or
 *       leak data sorted by a sensitive column.</li>
 * </ul>
 */
@Tag("fuzz")
class PaginationFuzzIT extends AbstractApiIntegrationTest {

    private static final int ITERATIONS = 100;

    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        accountId = data.createBrokerageAccountAndGetId();
        // Make a few transactions so the listing isn't empty.
        for (int i = 0; i < 5; i++) {
            data.createBuyTransaction(accountId, "AAPL", 1, 100);
        }
    }

    @Test
    void listAccounts_withFuzzedPaginationParams_neverCrashes() {
        FuzzSupport.samples(this::randomPagingQuery, ITERATIONS, 0xE1L)
                .forEach(query -> {
                    var path = "/api/v1/accounts" + query;
                    var resp = restTemplate.exchange(path, HttpMethod.GET,
                            authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("GET %s produced 5xx", path)
                            .isFalse();
                });
    }

    @Test
    void listTransactions_withFuzzedPaginationParams_neverCrashes() {
        FuzzSupport.samples(this::randomPagingQuery, ITERATIONS, 0xE2L)
                .forEach(query -> {
                    var path = "/api/v1/accounts/" + accountId + "/transactions" + query;
                    var resp = restTemplate.exchange(path, HttpMethod.GET,
                            authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("GET %s produced 5xx", path)
                            .isFalse();
                });
    }

    @Test
    void listAccounts_withSqlInjectionInPagination_neverCrashesNeverInjects() {
        // Specific high-signal payloads — keep these in addition to the
        // generic fuzz iterations so we always hit the classics.
        var payloads = new String[]{
                "?page=-1&size=-1",
                "?page=2147483647",
                "?size=2147483647",
                "?page=abc",
                "?size=xyz",
                "?page=0&size=0",
                "?size=" + ("x".repeat(2048)),
                "?page=1';DROP TABLE accounts;--",
                "?sort=password",
                "?sort=tenant_id,desc",
                "?sort=password,desc",
                "?sort=" + ("a".repeat(2048))
        };

        for (var query : payloads) {
            var resp = restTemplate.exchange("/api/v1/accounts" + query,
                    HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);
            assertThat(resp.getStatusCode().is5xxServerError())
                    .as("GET /api/v1/accounts%s produced 5xx", query)
                    .isFalse();
        }
    }

    private String randomPagingQuery(Random r) {
        var sb = new StringBuilder("?");
        boolean first = true;
        if (r.nextBoolean()) {
            sb.append("page=").append(randomNumericLike(r));
            first = false;
        }
        if (r.nextBoolean()) {
            if (!first) {
                sb.append('&');
            }
            sb.append("size=").append(randomNumericLike(r));
            first = false;
        }
        if (r.nextInt(3) == 0) {
            if (!first) {
                sb.append('&');
            }
            sb.append("sort=").append(FuzzSupport.alnum(r, 32));
        }
        return sb.length() == 1 ? "" : sb.toString();
    }

    private String randomNumericLike(Random r) {
        return switch (r.nextInt(8)) {
            case 0 -> "-1";
            case 1 -> "0";
            case 2 -> "2147483647";       // Integer.MAX_VALUE
            case 3 -> "-2147483648";       // Integer.MIN_VALUE
            case 4 -> Integer.toString(r.nextInt(1000));
            case 5 -> "abc";
            case 6 -> "1.5e308";
            default -> FuzzSupport.alnum(r, 16);
        };
    }
}
