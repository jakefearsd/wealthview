package com.wealthview.core.pricefeed;

import java.time.LocalDate;
import java.util.Optional;

import com.wealthview.core.pricefeed.dto.CandleResponse;
import com.wealthview.core.pricefeed.dto.QuoteResult;

public interface PriceFeedClient {

    QuoteResult getQuote(String symbol);

    Optional<CandleResponse> getCandles(String symbol, LocalDate from, LocalDate to);
}
