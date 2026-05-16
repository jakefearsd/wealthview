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
}
