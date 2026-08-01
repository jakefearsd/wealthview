package com.wealthview.importmodule.finnhub;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FinnhubSplitClientTest {

    private MockRestServiceServer mockServer;
    private FinnhubSplitClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://finnhub.io");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new FinnhubSplitClient(builder.build(), "test-api-key");
    }

    @Test
    void fetch_validResponse_parsesCorrectly() {
        mockServer.expect(method(HttpMethod.GET))
                .andExpect(header("X-Finnhub-Token", "test-api-key"))
                .andExpect(queryParam("symbol", "AAPL"))
                .andRespond(withSuccess("""
                        [
                          {"symbol": "AAPL", "date": "2020-08-31", "fromFactor": 1.0, "toFactor": 4.0}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).date()).isEqualTo(LocalDate.of(2020, 8, 31));
        assertThat(result.get(0).numerator()).isEqualTo(4);
        assertThat(result.get(0).denominator()).isEqualTo(1);
        mockServer.verify();
    }

    @Test
    void fetch_emptyResponse_returnsEmptyList() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        var result = client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetch_finnhubServerError_returnsEmptyList() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withServerError());

        var result = client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetch_reverseSplit_parsedCorrectly() {
        // 1:8 reverse split (e.g. some ETF reorganization): fromFactor=8, toFactor=1
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"symbol": "XYZ", "date": "2022-05-01", "fromFactor": 8.0, "toFactor": 1.0}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetch("XYZ", LocalDate.of(2022, 1, 1), LocalDate.of(2023, 1, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).numerator()).isEqualTo(1);
        assertThat(result.get(0).denominator()).isEqualTo(8);
        mockServer.verify();
    }

    @Test
    void fetch_apiKeyTravelsInHeader_notQueryParam() {
        mockServer.expect(method(HttpMethod.GET))
                .andExpect(header("X-Finnhub-Token", "test-api-key"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));

        mockServer.verify();
    }

    // === Rows and responses that are not the happy path ===
    //
    // This client feeds StockSplitSyncService, which rewrites transaction quantities and holding
    // share counts. A malformed row that slipped through as a 0:1 or an inverted ratio would
    // silently corrupt every affected position, so the skip paths matter more than the parse path.
    // None of them were covered.

    @Test
    void fetch_rowWithMissingFields_isSkippedWhileValidRowsSurvive() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"symbol": "AAPL", "date": null, "fromFactor": 1.0, "toFactor": 4.0},
                          {"symbol": "AAPL", "date": "2020-08-31", "fromFactor": null, "toFactor": 4.0},
                          {"symbol": "AAPL", "date": "2020-08-31", "fromFactor": 1.0, "toFactor": null},
                          {"symbol": "AAPL", "date": "2022-06-06", "fromFactor": 1.0, "toFactor": 20.0}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2023, 1, 1));

        assertThat(result).singleElement().satisfies(split -> {
            assertThat(split.date()).isEqualTo(LocalDate.of(2022, 6, 6));
            assertThat(split.numerator()).isEqualTo(20);
            assertThat(split.denominator()).isEqualTo(1);
        });
        mockServer.verify();
    }

    @Test
    void fetch_rowWithZeroFactors_isSkippedRatherThanProducingAZeroRatio() {
        // A 0 factor would round to a 0 numerator/denominator, which DetectedSplit rejects. The
        // client must swallow that and drop the row instead of failing the whole sync.
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"symbol": "AAPL", "date": "2020-08-31", "fromFactor": 0.0, "toFactor": 0.0},
                          {"symbol": "AAPL", "date": "2022-06-06", "fromFactor": 1.0, "toFactor": 20.0}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2023, 1, 1));

        assertThat(result).singleElement()
                .satisfies(split -> assertThat(split.numerator()).isEqualTo(20));
        mockServer.verify();
    }

    @Test
    void fetch_fractionalFactors_areReducedToLowestTerms() {
        // Finnhub returns doubles; the client scales by 1000 and divides by the GCD. A 3:2 split
        // arrives as 1.5/1.0 and must not surface as 1500:1000.
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"symbol": "XYZ", "date": "2024-05-01", "fromFactor": 1.0, "toFactor": 1.5}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetch("XYZ", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1));

        assertThat(result).singleElement().satisfies(split -> {
            assertThat(split.numerator()).isEqualTo(3);
            assertThat(split.denominator()).isEqualTo(2);
        });
        mockServer.verify();
    }

    @Test
    void fetch_unauthorized_returnsEmptyListRatherThanThrowing() {
        // A wrong or expired API key must degrade to "no splits detected", not abort the sync.
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThat(client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1))).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetch_rateLimited_returnsEmptyList() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        assertThat(client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1))).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetch_connectionFailure_returnsEmptyList() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        assertThat(client.fetch("AAPL", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1))).isEmpty();
        mockServer.verify();
    }
}
