package com.wealthview.importmodule.finnhub;

import com.wealthview.core.pricefeed.PriceFeedClient;
import com.wealthview.core.pricefeed.dto.CandleResponse;
import com.wealthview.core.pricefeed.dto.CandleResponse.CandleEntry;
import com.wealthview.core.pricefeed.dto.QuoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

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

    private final RestClient restClient;
    private final String apiKey;

    public FinnhubClient(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public Optional<QuoteResponse> getQuote(String symbol) {
        try {
            var dto = restClient.get()
                    .uri("/api/v1/quote?symbol={symbol}&token={token}", symbol, apiKey)
                    .retrieve()
                    .body(FinnhubQuoteDto.class);

            if (dto == null || dto.c() == null || dto.c().compareTo(BigDecimal.ZERO) == 0) {
                log.warn("No valid quote data for symbol {}", symbol);
                return Optional.empty();
            }

            return Optional.of(new QuoteResponse(symbol, dto.c()));
        } catch (Exception e) {
            log.warn("Failed to fetch quote for symbol {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<CandleResponse> getCandles(String symbol, LocalDate from, LocalDate to) {
        try {
            long fromUnix = from.atStartOfDay(NY_ZONE).toEpochSecond();
            long toUnix = to.atTime(23, 59, 59).atZone(NY_ZONE).toEpochSecond();

            var dto = restClient.get()
                    .uri("/api/v1/stock/candle?symbol={symbol}&resolution=D&from={from}&to={to}&token={token}",
                            symbol, fromUnix, toUnix, apiKey)
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
        } catch (Exception e) {
            log.warn("Failed to fetch candles for symbol {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    record FinnhubQuoteDto(BigDecimal c) {}

    record FinnhubCandleDto(List<BigDecimal> c, List<Long> t, String s) {}
}
