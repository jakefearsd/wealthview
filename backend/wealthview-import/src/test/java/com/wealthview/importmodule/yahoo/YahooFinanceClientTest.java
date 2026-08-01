package com.wealthview.importmodule.yahoo;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooFinanceClientTest {

    private MockRestServiceServer mockServer;
    private YahooFinanceClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new YahooFinanceClient(builder.build(), 0);
    }

    @Test
    void fetchHistory_successfulResponse_returnsPricePoints() {
        // Timestamps for 2024-01-02 14:30 UTC and 2024-01-03 14:30 UTC (market hours)
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": [{
                                    "timestamp": [1704196200, 1704282600],
                                    "indicators": {
                                        "quote": [{
                                            "close": [185.50, 186.25]
                                        }]
                                    }
                                }],
                                "error": null
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3));

        assertThat(result.failed()).isFalse();
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().get(0).closePrice()).isEqualByComparingTo("185.50");
        assertThat(result.points().get(1).closePrice()).isEqualByComparingTo("186.25");
        mockServer.verify();
    }

    @Test
    void fetchHistory_timestampsConvertToCorrectDates() {
        // 1704196200 = 2024-01-02 09:30 EST (market open) = 2024-01-02 14:30 UTC
        // Adding 12h for correct date: still 2024-01-02
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": [{
                                    "timestamp": [1704196200],
                                    "indicators": {
                                        "quote": [{
                                            "close": [185.50]
                                        }]
                                    }
                                }],
                                "error": null
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.points()).hasSize(1);
        assertThat(result.points().get(0).date()).isEqualTo(LocalDate.of(2024, 1, 2));
        mockServer.verify();
    }

    @Test
    void fetchHistory_nullCloseValues_skipped() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": [{
                                    "timestamp": [1704196200, 1704282600],
                                    "indicators": {
                                        "quote": [{
                                            "close": [185.50, null]
                                        }]
                                    }
                                }],
                                "error": null
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.points()).hasSize(1);
        assertThat(result.points().get(0).closePrice()).isEqualByComparingTo("185.50");
        mockServer.verify();
    }

    @Test
    void fetchHistory_emptyResult_returnsFailureWithReason() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": [],
                                "error": null
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchHistory("INVALID",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.failed()).isTrue();
        assertThat(result.points()).isEmpty();
        assertThat(result.errorReason()).contains("no price data");
        mockServer.verify();
    }

    @Test
    void fetchHistory_serverError_returnsFailureWithReason() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withServerError());

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.failed()).isTrue();
        assertThat(result.points()).isEmpty();
        assertThat(result.errorReason()).contains("HTTP");
        mockServer.verify();
    }

    @Test
    void fetchHistory_chartError_returnsFailureWithReason() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": null,
                                "error": {
                                    "code": "Not Found",
                                    "description": "No data found"
                                }
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchHistory("INVALID",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.failed()).isTrue();
        assertThat(result.points()).isEmpty();
        assertThat(result.errorReason()).contains("no price data");
        mockServer.verify();
    }

    @Test
    void fetchCurrentPrice_successfulResponse_returnsPrice() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": [{
                                    "timestamp": [1704196200],
                                    "indicators": {
                                        "quote": [{
                                            "close": [185.50]
                                        }]
                                    }
                                }],
                                "error": null
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchCurrentPrice("AAPL");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("185.50");
        mockServer.verify();
    }

    @Test
    void fetchCurrentPrice_serverError_returnsEmpty() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withServerError());

        var result = client.fetchCurrentPrice("AAPL");

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetchCurrentPrice_emptyCloseList_returnsEmpty() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": [{
                                    "timestamp": [],
                                    "indicators": {
                                        "quote": [{
                                            "close": []
                                        }]
                                    }
                                }],
                                "error": null
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchCurrentPrice("AAPL");

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetchHistory_missingQuoteIndicators_returnsNoPriceDataFailure() {
        // A result entry without indicators.quote must parse as "no data", not
        // trip an internal NPE that gets reported as an unexpected format.
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": [{
                                    "timestamp": [1704196200]
                                }],
                                "error": null
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3));

        assertThat(result.failed()).isTrue();
        assertThat(result.errorReason()).contains("no price data");
        mockServer.verify();
    }

    @Test
    void fetchHistory_emptyQuoteArray_returnsNoPriceDataFailure() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "chart": {
                                "result": [{
                                    "timestamp": [1704196200],
                                    "indicators": {
                                        "quote": []
                                    }
                                }],
                                "error": null
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3));

        assertThat(result.failed()).isTrue();
        assertThat(result.errorReason()).contains("no price data");
        mockServer.verify();
    }

    // === Failure modes of a free, unauthenticated third-party API ===
    //
    // Yahoo's endpoint is not a contract we control: delisted or mistyped symbols come back 404,
    // heavy use gets rate-limited, and an outage can return an HTML error page where JSON is
    // expected. Each maps to a DIFFERENT branch and a different user-visible reason, and only the
    // 5xx one was covered — so a regression that turned a 404 into an unhandled exception, or
    // collapsed the distinct reasons into one, would not have been caught.

    @Test
    void fetchHistory_notFound_reportsTheClientErrorStatus() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        var result = client.fetchHistory("NOSUCHTICKER",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.failed()).isTrue();
        assertThat(result.points()).isEmpty();
        assertThat(result.errorReason())
                .as("a 4xx must be distinguishable from a 5xx — one is the caller's symbol, the "
                        + "other is Yahoo being down")
                .isEqualTo("HTTP 404 from Yahoo Finance");
        mockServer.verify();
    }

    @Test
    void fetchHistory_rateLimited_reportsTheClientErrorStatus() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.failed()).isTrue();
        assertThat(result.errorReason()).isEqualTo("HTTP 429 from Yahoo Finance");
        mockServer.verify();
    }

    @Test
    void fetchHistory_connectionFailure_reportsANetworkError() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.failed()).isTrue();
        assertThat(result.errorReason()).startsWith("network error:");
        mockServer.verify();
    }

    @Test
    void fetchHistory_htmlErrorPageInsteadOfJson_reportsAFormatProblemRatherThanThrowing() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("<html><body>Service Unavailable</body></html>",
                        MediaType.APPLICATION_JSON));

        var result = client.fetchHistory("AAPL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result.failed()).isTrue();
        assertThat(result.errorReason()).isEqualTo("unexpected response format from Yahoo Finance");
        mockServer.verify();
    }

    @Test
    void fetchCurrentPrice_htmlErrorPageInsteadOfJson_returnsEmptyRatherThanThrowing() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("<html><body>nope</body></html>", MediaType.APPLICATION_JSON));

        assertThat(client.fetchCurrentPrice("AAPL")).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetchCurrentPrice_connectionFailure_returnsEmpty() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        assertThat(client.fetchCurrentPrice("AAPL")).isEmpty();
        mockServer.verify();
    }
}
