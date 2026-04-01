package com.wealthview.core.pricefeed.dto;

public sealed interface QuoteResult {

    record Success(QuoteResponse quote) implements QuoteResult {}

    record Failure(String reason) implements QuoteResult {}
}
