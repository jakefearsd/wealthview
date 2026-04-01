package com.wealthview.core.pricefeed;

import com.wealthview.core.pricefeed.dto.CandleResponse;
import com.wealthview.core.pricefeed.dto.QuoteResult;

import java.time.LocalDate;
import java.util.Optional;

public interface PriceFeedClient {

    QuoteResult getQuote(String symbol);

    Optional<CandleResponse> getCandles(String symbol, LocalDate from, LocalDate to);
}
