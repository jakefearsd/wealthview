package com.wealthview.core.split.dto;

import java.util.List;

/**
 * Outcome of a single sync run.
 */
public record SplitSyncResult(int symbolsScanned, int splitsDiscovered, int splitsApplied,
                              List<String> failedSymbols) {
}
