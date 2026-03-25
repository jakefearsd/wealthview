package com.wealthview.core.price.dto;

import java.util.List;

public record YahooSyncResult(int inserted, int updated, List<String> failed) {
}
