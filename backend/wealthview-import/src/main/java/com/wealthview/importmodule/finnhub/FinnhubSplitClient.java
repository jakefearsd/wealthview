package com.wealthview.importmodule.finnhub;

import com.wealthview.core.split.SplitDetectionClient;
import com.wealthview.core.split.dto.DetectedSplit;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Finnhub split-detection adapter. Calls
 * {@code /api/v1/stock/split?symbol=&from=&to=} and maps the response to a
 * list of {@link DetectedSplit}. On any HTTP or network error, returns an
 * empty list and logs a warning — splits are discovery-only and can retry
 * tomorrow.
 */
public class FinnhubSplitClient implements SplitDetectionClient {

    private static final Logger log = LoggerFactory.getLogger(FinnhubSplitClient.class);
    private static final String TOKEN_HEADER = "X-Finnhub-Token";

    private final RestClient restClient;
    private final String apiKey;

    public FinnhubSplitClient(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Timed(value = "wealthview.finnhub.splits", histogram = true)
    @Override
    public List<DetectedSplit> fetch(String symbol, LocalDate from, LocalDate to) {
        try {
            var dto = restClient.get()
                    .uri("/api/v1/stock/split?symbol={symbol}&from={from}&to={to}",
                            symbol, from.toString(), to.toString())
                    .header(TOKEN_HEADER, apiKey)
                    .retrieve()
                    .body(FinnhubSplitDto[].class);

            if (dto == null || dto.length == 0) {
                return List.of();
            }

            var results = new ArrayList<DetectedSplit>();
            for (var entry : dto) {
                if (entry.date() == null || entry.fromFactor() == null || entry.toFactor() == null) {
                    log.warn("Skipping malformed split row for {}: {}", symbol, entry);
                    continue;
                }
                // Finnhub semantics: fromFactor old shares -> toFactor new shares.
                // toFactor / fromFactor = ratio of new-to-old, i.e. numerator/denominator
                // for our model. Multiply through by 1000 to get integers since Finnhub
                // returns doubles like 1.0, 4.0, 0.5.
                int numerator = (int) Math.round(entry.toFactor() * 1000);
                int denominator = (int) Math.round(entry.fromFactor() * 1000);
                int g = gcd(numerator, denominator);
                if (g > 0) {
                    numerator /= g;
                    denominator /= g;
                }
                try {
                    results.add(new DetectedSplit(symbol,
                            LocalDate.parse(entry.date()), numerator, denominator));
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping invalid split for {} on {}: {}", symbol, entry.date(), e.getMessage());
                }
            }
            return results;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.warn("HTTP {} fetching splits for symbol {}", e.getStatusCode().value(), symbol);
            return List.of();
        } catch (RestClientException e) {
            log.warn("Failed to fetch splits for symbol {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    private static int gcd(int a, int b) {
        return b == 0 ? Math.abs(a) : gcd(b, a % b);
    }

    /**
     * Finnhub /stock/split response row.
     * <pre>{ "symbol": "AAPL", "date": "2020-08-31", "fromFactor": 1.0, "toFactor": 4.0 }</pre>
     */
    public record FinnhubSplitDto(String symbol, String date, Double fromFactor, Double toFactor) {}
}
