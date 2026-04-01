package com.wealthview.importmodule.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.wealthview.core.price.YahooPriceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class YahooFinanceClient implements YahooPriceClient {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceClient.class);
    private static final String CHART_PATH = "/v8/finance/chart/";

    private final RestClient restClient;
    private final long rateLimitMs;

    public YahooFinanceClient(RestClient restClient, long rateLimitMs) {
        this.restClient = restClient;
        this.rateLimitMs = rateLimitMs;
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public FetchResult fetchHistory(String symbol, LocalDate from, LocalDate to) {
        try {
            long period1 = from.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            long period2 = to.atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC);

            var response = restClient.get()
                    .uri(CHART_PATH + "{symbol}?period1={p1}&period2={p2}&interval=1d",
                            symbol, period1, period2)
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve()
                    .body(JsonNode.class);

            var points = parseChartResponse(response);
            sleepForRateLimit();
            if (points.isEmpty()) {
                return FetchResult.failure(
                        "no price data — symbol may not be listed on Yahoo Finance");
            }
            return FetchResult.success(points);
        } catch (HttpClientErrorException e) {
            log.warn("HTTP {} fetching Yahoo history for symbol {}", e.getStatusCode().value(), symbol, e);
            return FetchResult.failure(
                    "HTTP %d from Yahoo Finance".formatted(e.getStatusCode().value()));
        } catch (HttpServerErrorException e) {
            log.warn("HTTP {} fetching Yahoo history for symbol {}", e.getStatusCode().value(), symbol, e);
            return FetchResult.failure(
                    "Yahoo Finance returned HTTP %d".formatted(e.getStatusCode().value()));
        } catch (RestClientException e) {
            log.warn("Failed to fetch Yahoo history for symbol {}", symbol, e);
            return FetchResult.failure("network error: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Error parsing Yahoo response for symbol {}", symbol, e);
            return FetchResult.failure(
                    "unexpected response format from Yahoo Finance");
        }
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public Optional<BigDecimal> fetchCurrentPrice(String symbol) {
        try {
            var response = restClient.get()
                    .uri(CHART_PATH + "{symbol}?range=1d&interval=1d", symbol)
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve()
                    .body(JsonNode.class);

            var points = parseChartResponse(response);
            if (points.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(points.get(points.size() - 1).closePrice());
        } catch (RestClientException e) {
            log.warn("Failed to fetch Yahoo current price for symbol {}", symbol, e);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Error parsing Yahoo current price for symbol {}", symbol, e);
            return Optional.empty();
        }
    }

    private List<PricePoint> parseChartResponse(JsonNode response) {
        if (response == null) {
            return Collections.emptyList();
        }

        var chart = response.path("chart");
        var resultArray = chart.path("result");
        if (resultArray.isMissingNode() || resultArray.isNull() || !resultArray.isArray()
                || resultArray.isEmpty()) {
            return Collections.emptyList();
        }

        var result = resultArray.get(0);
        var timestamps = result.path("timestamp");
        var closeArray = result.path("indicators").path("quote").get(0).path("close");

        if (!timestamps.isArray() || !closeArray.isArray()) {
            return Collections.emptyList();
        }

        var points = new ArrayList<PricePoint>();
        for (int i = 0; i < timestamps.size(); i++) {
            var closeNode = closeArray.get(i);
            if (closeNode == null || closeNode.isNull()) {
                continue;
            }

            long epochSecond = timestamps.get(i).asLong();
            // Yahoo timestamps are market-open time; add 12 hours for correct date
            var date = Instant.ofEpochSecond(epochSecond + 43200)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
            var closePrice = closeNode.decimalValue();
            points.add(new PricePoint(date, closePrice));
        }

        return points;
    }

    private void sleepForRateLimit() {
        if (rateLimitMs > 0) {
            try {
                Thread.sleep(rateLimitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limit sleep interrupted");
            }
        }
    }
}
