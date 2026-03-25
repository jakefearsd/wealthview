package com.wealthview.core.price.dto;

import java.time.LocalDate;

public record PriceSyncStatus(String symbol, LocalDate latestDate, String source, boolean stale) {
}
