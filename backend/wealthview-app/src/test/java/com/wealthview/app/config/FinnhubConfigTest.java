package com.wealthview.app.config;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.wealthview.core.pricefeed.dto.QuoteResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the beans built by FinnhubConfig against a local HTTP server to pin the
 * real wiring: the configured base URL is applied to outgoing requests, the expected
 * Finnhub endpoint paths are hit, and the API key travels as the X-Finnhub-Token header.
 */
class FinnhubConfigTest {

    private final FinnhubConfig config = new FinnhubConfig();

    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> tokenHeader = new AtomicReference<>();
    private HttpServer server;
    private String responseJson = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().toString());
            tokenHeader.set(exchange.getRequestHeaders().getFirst("X-Finnhub-Token"));
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void finnhubClient_getQuote_hitsQuotePathOnConfiguredBaseUrlWithApiKeyHeader() {
        responseJson = "{\"c\": 123.45}";
        var finnhubClient = config.finnhubClient(config.finnhubRestClient(baseUrl()), "test-api-key");

        var result = finnhubClient.getQuote("AAPL");

        assertThat(result).isInstanceOf(QuoteResult.Success.class);
        var quote = ((QuoteResult.Success) result).quote();
        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.currentPrice()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(requestedPath.get()).isEqualTo("/api/v1/quote?symbol=AAPL");
        assertThat(tokenHeader.get()).isEqualTo("test-api-key");
    }

    @Test
    void finnhubSplitClient_fetch_hitsSplitPathOnConfiguredBaseUrlWithApiKeyHeader() {
        responseJson = "[{\"symbol\": \"NVDA\", \"date\": \"2024-06-10\", \"fromFactor\": 1.0, \"toFactor\": 10.0}]";
        var splitClient = config.finnhubSplitClient(config.finnhubRestClient(baseUrl()), "split-api-key");

        var splits = splitClient.fetch("NVDA", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(splits).hasSize(1);
        assertThat(splits.getFirst().symbol()).isEqualTo("NVDA");
        assertThat(splits.getFirst().date()).isEqualTo(LocalDate.of(2024, 6, 10));
        assertThat(splits.getFirst().numerator()).isEqualTo(10);
        assertThat(splits.getFirst().denominator()).isEqualTo(1);
        assertThat(requestedPath.get())
                .isEqualTo("/api/v1/stock/split?symbol=NVDA&from=2024-01-01&to=2024-12-31");
        assertThat(tokenHeader.get()).isEqualTo("split-api-key");
    }
}
