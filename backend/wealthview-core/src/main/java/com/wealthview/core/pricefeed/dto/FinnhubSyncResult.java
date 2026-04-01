package com.wealthview.core.pricefeed.dto;

import java.util.List;

public record FinnhubSyncResult(int succeeded, int total, List<SymbolError> failures) {

    public record SymbolError(String symbol, String reason) {}
}
