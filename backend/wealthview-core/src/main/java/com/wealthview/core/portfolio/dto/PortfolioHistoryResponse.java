package com.wealthview.core.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PortfolioHistoryResponse(
        UUID accountId,
        List<PortfolioDataPointDto> dataPoints,
        List<String> symbols,
        int weeks,
        boolean hasMoneyMarketHoldings,
        BigDecimal moneyMarketTotal) {}
