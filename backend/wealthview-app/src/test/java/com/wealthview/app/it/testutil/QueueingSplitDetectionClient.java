package com.wealthview.app.it.testutil;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wealthview.core.split.SplitDetectionClient;
import com.wealthview.core.split.dto.DetectedSplit;

/**
 * Queue-based stub {@link SplitDetectionClient} shared by the split-package
 * ITs that need to hand the sync/backfill machinery a scripted set of
 * detected splits — and, for sync, a scripted per-symbol failure — without a
 * real Finnhub key. Superset of the two near-identical stub clients this
 * replaces: {@code StockSplitSyncIT}'s (queue + failure + reset) and
 * {@code StockSplitBackfillIT}'s (queue + reset subset).
 */
public class QueueingSplitDetectionClient implements SplitDetectionClient {

    private final Map<String, List<DetectedSplit>> queued = new HashMap<>();
    private final Set<String> failures = new HashSet<>();

    // synchronized: splits are queued on the JUnit thread but fetched on the
    // server's HTTP worker threads — a bare HashMap has no cross-thread
    // memory-visibility guarantee.
    public synchronized void queueSplit(String symbol, DetectedSplit split) {
        queued.computeIfAbsent(symbol, k -> new ArrayList<>()).add(split);
    }

    public synchronized void queueFailure(String symbol) {
        failures.add(symbol);
    }

    public synchronized void reset() {
        queued.clear();
        failures.clear();
    }

    @Override
    public synchronized List<DetectedSplit> fetch(String symbol, LocalDate from, LocalDate to) {
        if (failures.contains(symbol)) {
            throw new RuntimeException("simulated finnhub failure");
        }
        var list = queued.remove(symbol);
        return list == null ? List.of() : list;
    }
}
