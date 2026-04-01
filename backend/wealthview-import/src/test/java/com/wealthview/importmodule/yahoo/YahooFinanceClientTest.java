package com.wealthview.importmodule.yahoo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
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
}
