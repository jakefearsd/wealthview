package com.wealthview.importmodule.finnhub;

import com.wealthview.core.pricefeed.dto.QuoteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FinnhubClientTest {

    private MockRestServiceServer mockServer;
    private FinnhubClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://finnhub.io");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new FinnhubClient(builder.build(), "test-api-key");
    }

    @Test
    void getQuote_successfulResponse_returnsSuccess() {
        mockServer.expect(requestTo("https://finnhub.io/api/v1/quote?symbol=AAPL&token=test-api-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"c": 150.25, "d": -1.5, "dp": -0.99, "h": 152.0, "l": 149.5, "o": 151.0, "pc": 151.75}
                        """, MediaType.APPLICATION_JSON));

        var result = client.getQuote("AAPL");

        assertThat(result).isInstanceOf(QuoteResult.Success.class);
        var success = (QuoteResult.Success) result;
        assertThat(success.quote().symbol()).isEqualTo("AAPL");
        assertThat(success.quote().currentPrice()).isEqualByComparingTo("150.25");
        mockServer.verify();
    }

    @Test
    void getQuote_serverError_returnsFailureWithStatusCode() {
        mockServer.expect(requestTo("https://finnhub.io/api/v1/quote?symbol=AAPL&token=test-api-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        var result = client.getQuote("AAPL");

        assertThat(result).isInstanceOf(QuoteResult.Failure.class);
        var failure = (QuoteResult.Failure) result;
        assertThat(failure.reason()).contains("500");
        mockServer.verify();
    }

    @Test
    void getQuote_forbidden_returnsFailureWithStatusCode() {
        mockServer.expect(requestTo("https://finnhub.io/api/v1/quote?symbol=AAPL&token=test-api-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        var result = client.getQuote("AAPL");

        assertThat(result).isInstanceOf(QuoteResult.Failure.class);
        var failure = (QuoteResult.Failure) result;
        assertThat(failure.reason()).contains("403");
        mockServer.verify();
    }

    @Test
    void getQuote_zeroPriceResponse_returnsFailureWithReason() {
        mockServer.expect(requestTo("https://finnhub.io/api/v1/quote?symbol=INVALID&token=test-api-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"c": 0, "d": null, "dp": null, "h": 0, "l": 0, "o": 0, "pc": 0}
                        """, MediaType.APPLICATION_JSON));

        var result = client.getQuote("INVALID");

        assertThat(result).isInstanceOf(QuoteResult.Failure.class);
        var failure = (QuoteResult.Failure) result;
        assertThat(failure.reason()).contains("no quote data");
        mockServer.verify();
    }

    @Test
    void getCandles_successfulResponse_returnsCandleEntries() {
        // Unix timestamps: 2024-01-02 and 2024-01-03 in America/New_York
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "c": [185.50, 186.25],
                            "h": [186.0, 187.0],
                            "l": [184.0, 185.0],
                            "o": [185.0, 185.5],
                            "s": "ok",
                            "t": [1704171600, 1704258000],
                            "v": [1000000, 1200000]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.getCandles("AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result).isPresent();
        assertThat(result.get().symbol()).isEqualTo("AAPL");
        assertThat(result.get().entries()).hasSize(2);
        assertThat(result.get().entries().get(0).closePrice()).isEqualByComparingTo("185.50");
        assertThat(result.get().entries().get(1).closePrice()).isEqualByComparingTo("186.25");
        mockServer.verify();
    }

    @Test
    void getCandles_noData_returnsEmpty() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"s": "no_data"}
                        """, MediaType.APPLICATION_JSON));

        var result = client.getCandles("AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void getCandles_apiError_returnsEmpty() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withServerError());

        var result = client.getCandles("AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result).isEmpty();
        mockServer.verify();
    }
}
