package com.wealthview.core.pricefeed.dto;

import java.math.BigDecimal;

public record QuoteResponse(String symbol, BigDecimal currentPrice) {}
