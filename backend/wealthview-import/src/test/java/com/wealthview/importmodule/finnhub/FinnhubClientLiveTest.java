package com.wealthview.importmodule.finnhub;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FinnhubClientLiveTest {

    private static FinnhubClient client;

    @BeforeAll
    static void setUp() throws IOException {
        var keysPath = Path.of(System.getProperty("user.dir")).getParent().getParent().resolve(".keys");
        assumeTrue(Files.exists(keysPath), ".keys file not found at " + keysPath);

        var apiKey = Files.readString(keysPath).lines()
                .filter(line -> line.startsWith("FINNHUB_API_KEY="))
                .map(line -> line.substring("FINNHUB_API_KEY=".length()).trim())
                .findFirst()
                .orElse(null);
        assumeTrue(apiKey != null && !apiKey.isBlank(), "FINNHUB_API_KEY not found in .keys");

        var restClient = RestClient.builder()
                .baseUrl("https://finnhub.io")
                .build();
        client = new FinnhubClient(restClient, apiKey);
    }

    @Test
    void getQuote_realSymbols_returnsPrices() throws InterruptedException {
        var symbols = new String[]{"GOOG", "AMZN"};

        for (var symbol : symbols) {
            var result = client.getQuote(symbol);

            assertThat(result).as("Quote for %s", symbol).isPresent();
            assertThat(result.get().symbol()).isEqualTo(symbol);
            assertThat(result.get().currentPrice()).isPositive();

            Thread.sleep(1100);
        }
    }

    @Test
    void getQuote_etfSymbols_returnsPrices() throws InterruptedException {
        Thread.sleep(1100);

        var symbols = new String[]{"VOO", "SCHD"};

        for (var symbol : symbols) {
            var result = client.getQuote(symbol);

            assertThat(result).as("Quote for %s", symbol).isPresent();
            assertThat(result.get().symbol()).isEqualTo(symbol);
            assertThat(result.get().currentPrice()).isPositive();

            Thread.sleep(1100);
        }
    }
}
