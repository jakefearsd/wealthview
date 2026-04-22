package com.wealthview.importmodule.finnhub;

import com.wealthview.core.pricefeed.PriceFeedClient;
import com.wealthview.core.pricefeed.dto.CandleResponse;
import com.wealthview.core.pricefeed.dto.CandleResponse.CandleEntry;
import com.wealthview.core.pricefeed.dto.QuoteResponse;
import com.wealthview.core.pricefeed.dto.QuoteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FinnhubClient implements PriceFeedClient {

    private static final Logger log = LoggerFactory.getLogger(FinnhubClient.class);
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final String TOKEN_HEADER = "X-Finnhub-Token";

    private final RestClient restClient;
    private final String apiKey;

    public FinnhubClient(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public QuoteResult getQuote(String symbol) {
        try {
            var dto = restClient.get()
                    .uri("/api/v1/quote?symbol={symbol}", symbol)
                    .header(TOKEN_HEADER, apiKey)
                    .retrieve()
                    .body(FinnhubQuoteDto.class);

            if (dto == null || dto.c() == null || dto.c().compareTo(BigDecimal.ZERO) == 0) {
                log.warn("No valid quote data for symbol {}", symbol);
                return new QuoteResult.Failure(
                        "no quote data — symbol may not be covered by Finnhub");
            }

            return new QuoteResult.Success(new QuoteResponse(symbol, dto.c()));
        } catch (HttpClientErrorException e) {
            log.warn("HTTP {} fetching quote for symbol {}", e.getStatusCode().value(), symbol, e);
            return new QuoteResult.Failure(
                    "HTTP %d from Finnhub — check API key permissions".formatted(e.getStatusCode().value()));
        } catch (HttpServerErrorException e) {
            log.warn("HTTP {} fetching quote for symbol {}", e.getStatusCode().value(), symbol, e);
            return new QuoteResult.Failure(
                    "Finnhub returned HTTP %d".formatted(e.getStatusCode().value()));
        } catch (RestClientException e) {
            log.warn("Failed to fetch quote for symbol {}", symbol, e);
            return new QuoteResult.Failure("network error: " + e.getMessage());
        }
    }

    @Override
    public Optional<CandleResponse> getCandles(String symbol, LocalDate from, LocalDate to) {
        try {
            long fromUnix = from.atStartOfDay(NY_ZONE).toEpochSecond();
            long toUnix = to.atTime(23, 59, 59).atZone(NY_ZONE).toEpochSecond();

            var dto = restClient.get()
                    .uri("/api/v1/stock/candle?symbol={symbol}&resolution=D&from={from}&to={to}",
                            symbol, fromUnix, toUnix)
                    .header(TOKEN_HEADER, apiKey)
                    .retrieve()
                    .body(FinnhubCandleDto.class);

            if (dto == null || !"ok".equals(dto.s())) {
                log.warn("No candle data for symbol {}", symbol);
                return Optional.empty();
            }

            var entries = new ArrayList<CandleEntry>();
            for (int i = 0; i < dto.c().size(); i++) {
                var date = Instant.ofEpochSecond(dto.t().get(i))
                        .atZone(NY_ZONE)
                        .toLocalDate();
                entries.add(new CandleEntry(date, dto.c().get(i)));
            }

            return Optional.of(new CandleResponse(symbol, entries));
        } catch (RestClientException e) {
            log.warn("Failed to fetch candles for symbol {}", symbol, e);
            return Optional.empty();
        }
    }

    record FinnhubQuoteDto(BigDecimal c) {}

    record FinnhubCandleDto(List<BigDecimal> c, List<Long> t, String s) {}
}
