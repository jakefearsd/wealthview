package com.wealthview.core.split;

import java.time.LocalDate;
import java.util.List;

import com.wealthview.core.split.dto.DetectedSplit;

/**
 * Source of stock-split events (Finnhub today, potentially Yahoo later).
 * Implementations live in {@code wealthview-import} per the import/parser pattern.
 *
 * <p>Failure handling: on network/HTTP errors implementations MUST return an
 * empty list and log a warning. Splits are discovery-only — a downstream
 * empty list simply means "try again tomorrow", not "no splits exist".
 */
public interface SplitDetectionClient {

    List<DetectedSplit> fetch(String symbol, LocalDate from, LocalDate to);
}
