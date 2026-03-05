package com.wealthview.core.pricefeed;

import com.wealthview.core.pricefeed.dto.CandleResponse;
import com.wealthview.core.pricefeed.dto.QuoteResponse;

import java.time.LocalDate;
import java.util.Optional;

public interface PriceFeedClient {

    Optional<QuoteResponse> getQuote(String symbol);

    Optional<CandleResponse> getCandles(String symbol, LocalDate from, LocalDate to);
}
