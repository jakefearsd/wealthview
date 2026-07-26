package com.wealthview.core.split;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.auth.CrossTenant;
import com.wealthview.core.config.EvictPriceDerivedCaches;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.holding.HoldingsComputationService;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.StockSplitAdjustmentEntity;
import com.wealthview.persistence.entity.StockSplitEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.StockSplitAdjustmentRepository;
import com.wealthview.persistence.repository.StockSplitRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Apply and unapply stock splits across the entire tenant population.
 *
 * <p>Splits are <b>global market events</b>: a 4:1 split of AAPL means every
 * holder of AAPL across every tenant ends up with 4x the shares at 1/4 the
 * price. The {@code stock_splits} table records the event itself (no tenant
 * scoping), and {@code stock_split_adjustments} records the row-level
 * before/after values per tenant so the apply is reversible.
 *
 * <p>Per-share price is implicit in WealthView's transaction model: a
 * transaction has {@code quantity} (shares) and {@code amount} (total dollars).
 * Splits multiply {@code quantity} by {@code numerator/denominator}; the
 * total dollar amount stays the same (you paid the same total when you
 * bought). Historical prices are stored as a per-share close; those are
 * multiplied by {@code denominator/numerator}.
 */
@Service
public class StockSplitService {

    private static final Logger log = LoggerFactory.getLogger(StockSplitService.class);
    private static final int VALUE_SCALE = 8;
    private static final RoundingMode VALUE_ROUNDING = RoundingMode.HALF_UP;

    private final StockSplitRepository stockSplitRepository;
    private final StockSplitAdjustmentRepository adjustmentRepository;
    private final TransactionRepository transactionRepository;
    private final PriceRepository priceRepository;
    private final HoldingRepository holdingRepository;
    private final HoldingsComputationService holdingsComputationService;
    private final MeterRegistry meterRegistry;
    private final boolean adjustHistoricalPrices;

    public StockSplitService(StockSplitRepository stockSplitRepository,
                             StockSplitAdjustmentRepository adjustmentRepository,
                             TransactionRepository transactionRepository,
                             PriceRepository priceRepository,
                             HoldingRepository holdingRepository,
                             HoldingsComputationService holdingsComputationService,
                             MeterRegistry meterRegistry,
                             @Value("${app.stock-splits.adjust-historical-prices:true}")
                             boolean adjustHistoricalPrices) {
        this.stockSplitRepository = stockSplitRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.transactionRepository = transactionRepository;
        this.priceRepository = priceRepository;
        this.holdingRepository = holdingRepository;
        this.holdingsComputationService = holdingsComputationService;
        this.meterRegistry = meterRegistry;
        this.adjustHistoricalPrices = adjustHistoricalPrices;
    }

    /**
     * Apply a split retroactively. Idempotent: a second call for the same
     * {@code (symbol, effectiveDate)} returns the existing row and does no
     * work.
     *
     * <p>{@code @CrossTenant}: splits are global market events — this method
     * must read and adjust the transactions of <em>every</em> tenant holding
     * the symbol, so the {@code tenantFilter} must never scope its queries to
     * whatever Authentication the calling thread happens to carry. Without the
     * bypass, a tenant-scoped (or stale) principal silently shrinks the reads
     * to one tenant: the split row is recorded globally while other tenants'
     * transactions and holdings stay un-adjusted (observed as
     * "0 tenants, 0 transactions, 0 prices adjusted" in hosted CI runs
     * 29195289770..29203911100). Safe: callers are the SUPER_ADMIN-only admin
     * endpoint, the scheduled sync, and the startup backfill; nothing here
     * returns cross-tenant data to a caller.
     */
    @CrossTenant
    @EvictPriceDerivedCaches
    @Transactional
    public StockSplitEntity applySplit(String symbol, LocalDate effectiveDate,
                                       int numerator, int denominator, String source) {
        validateRatio(numerator, denominator);
        var existing = stockSplitRepository.findBySymbolAndEffectiveDate(symbol, effectiveDate);
        if (existing.isPresent()) {
            log.info("Split for {} on {} already applied; skipping", symbol, effectiveDate);
            return existing.orElseThrow();
        }

        var split = stockSplitRepository.save(
                new StockSplitEntity(symbol, effectiveDate, numerator, denominator, source));

        var affectedTenantIds = transactionRepository.findDistinctTenantIdsBySymbol(symbol);

        int txnCount = adjustTransactions(split, symbol, effectiveDate);
        int priceCount = adjustPrices(split, symbol, effectiveDate, affectedTenantIds);

        warnOnManualOverrideHoldings(symbol, affectedTenantIds);

        for (var tenantId : affectedTenantIds) {
            holdingsComputationService.recomputeAllForTenantAndSymbol(tenantId, symbol);
        }

        meterRegistry.counter("wealthview.splits.applied",
                "symbol", symbol,
                "ratio", numerator + ":" + denominator).increment();

        log.info("Applied split {}:{} for {} on {}: {} tenants, {} transactions, {} prices adjusted",
                numerator, denominator, symbol, effectiveDate,
                affectedTenantIds.size(), txnCount, priceCount);
        return split;
    }

    /**
     * Reverse a previously-applied split. All adjustment rows are read back
     * and the captured old values restored. The split row itself is deleted,
     * which cascades-deletes the adjustment rows.
     *
     * <p>{@code @CrossTenant}: same rationale as {@link #applySplit} — the
     * reversal must restore every affected tenant's rows regardless of the
     * calling thread's Authentication.
     */
    @CrossTenant
    @EvictPriceDerivedCaches
    @Transactional
    public void unapplySplit(UUID splitId) {
        var split = stockSplitRepository.findById(splitId)
                .orElseThrow(() -> new EntityNotFoundException("Stock split not found: " + splitId));

        var adjustments = adjustmentRepository.findBySplit_Id(splitId);

        var txnAdjustments = new ArrayList<StockSplitAdjustmentEntity>();
        var priceAdjustments = new ArrayList<StockSplitAdjustmentEntity>();
        for (var adj : adjustments) {
            switch (adj.getTargetTable()) {
                case "transactions" -> txnAdjustments.add(adj);
                case "prices" -> priceAdjustments.add(adj);
                default -> log.warn("Unknown target_table on split adjustment: {}", adj.getTargetTable());
            }
        }
        int txnRestored = restoreTransactions(txnAdjustments);
        int priceRestored = restorePrices(split, priceAdjustments);

        var symbol = split.getSymbol();
        var tenantIds = transactionRepository.findDistinctTenantIdsBySymbol(symbol);
        // Delete adjustment rows first so the cascade from stock_splits doesn't
        // leave Hibernate with persistent adjustment entities referencing a
        // deleted-but-not-yet-flushed split (TransientObjectException).
        adjustmentRepository.deleteAll(adjustments);
        adjustmentRepository.flush();
        stockSplitRepository.delete(split);
        stockSplitRepository.flush();
        for (var tenantId : tenantIds) {
            holdingsComputationService.recomputeAllForTenantAndSymbol(tenantId, symbol);
        }

        meterRegistry.counter("wealthview.splits.unapplied",
                "symbol", symbol).increment();

        log.info("Unapplied split {} for {} on {}: {} transactions, {} prices restored",
                splitId, symbol, split.getEffectiveDate(), txnRestored, priceRestored);
    }

    private int adjustTransactions(StockSplitEntity split, String symbol, LocalDate effectiveDate) {
        var txns = transactionRepository.findBySymbolAndDateOnOrBefore(symbol, effectiveDate);
        int adjusted = 0;
        for (var txn : txns) {
            if (txn.getQuantity() == null || txn.getQuantity().signum() == 0) {
                continue;
            }
            var oldQty = txn.getQuantity();
            var newQty = SplitMath.adjustShares(oldQty, split.getNumerator(), split.getDenominator());
            txn.setQuantity(newQty);

            adjustmentRepository.save(new StockSplitAdjustmentEntity(
                    split, txn.getTenantId(), "transactions", txn.getId(),
                    "quantity",
                    oldQty.setScale(VALUE_SCALE, VALUE_ROUNDING),
                    newQty.setScale(VALUE_SCALE, VALUE_ROUNDING)));
            adjusted++;
        }
        return adjusted;
    }

    private int adjustPrices(StockSplitEntity split, String symbol, LocalDate effectiveDate,
                             List<UUID> affectedTenantIds) {
        if (!adjustHistoricalPrices) {
            return 0;
        }
        if (affectedTenantIds.isEmpty()) {
            // No tenants hold this symbol — recording price adjustments would
            // have nowhere to associate. Skip; the split row itself is still
            // recorded for future buyers.
            return 0;
        }
        var anchorTenantId = affectedTenantIds.getFirst();
        var prices = priceRepository.findBySymbolAndDateBefore(symbol, effectiveDate);
        int adjusted = 0;
        for (var price : prices) {
            var oldClose = price.getClosePrice();
            var newClose = SplitMath.adjustPrice(oldClose, split.getNumerator(), split.getDenominator());
            price.setClosePrice(newClose);

            adjustmentRepository.save(new StockSplitAdjustmentEntity(
                    split, anchorTenantId, "prices", priceUuid(symbol, price.getDate()),
                    "close_price",
                    oldClose.setScale(VALUE_SCALE, VALUE_ROUNDING),
                    newClose.setScale(VALUE_SCALE, VALUE_ROUNDING)));
            adjusted++;
        }
        return adjusted;
    }

    private void warnOnManualOverrideHoldings(String symbol, List<UUID> tenantIds) {
        for (var tenantId : tenantIds) {
            for (var holding : holdingRepository.findByTenant_Id(tenantId)) {
                if (symbol.equals(holding.getSymbol()) && holding.isManualOverride()) {
                    log.warn("Manual-override holding {} for tenant {} symbol {} not auto-adjusted; verify manually",
                            holding.getId(), tenantId, symbol);
                }
            }
        }
    }

    private int restoreTransactions(List<StockSplitAdjustmentEntity> adjustments) {
        if (adjustments.isEmpty()) {
            return 0;
        }
        var targetIds = adjustments.stream().map(StockSplitAdjustmentEntity::getTargetRowId).toList();
        var txnsById = new HashMap<UUID, TransactionEntity>();
        for (var txn : transactionRepository.findAllById(targetIds)) {
            txnsById.put(txn.getId(), txn);
        }

        int restored = 0;
        for (var adj : adjustments) {
            var txn = txnsById.get(adj.getTargetRowId());
            if (txn == null) {
                log.warn("Transaction {} referenced by split adjustment {} no longer exists",
                        adj.getTargetRowId(), adj.getId());
                continue;
            }
            if (!"quantity".equals(adj.getFieldName())) {
                log.warn("Unknown transaction field on split adjustment: {}", adj.getFieldName());
                continue;
            }
            txn.setQuantity(adj.getOldValue().setScale(4, VALUE_ROUNDING));
            restored++;
        }
        return restored;
    }

    private int restorePrices(StockSplitEntity split, List<StockSplitAdjustmentEntity> adjustments) {
        if (adjustments.isEmpty()) {
            return 0;
        }
        // The adjustment's target_row_id is the deterministic UUID created from
        // (symbol, date) at apply time — it cannot be reversed into a lookup key.
        // Load the symbol's price history once and index it by that UUID so each
        // adjustment restores via a map hit instead of rescanning every price row.
        var pricesByUuid = new HashMap<UUID, PriceEntity>();
        for (var price : priceRepository.findBySymbolAndDateBefore(split.getSymbol(), split.getEffectiveDate())) {
            pricesByUuid.put(priceUuid(price.getSymbol(), price.getDate()), price);
        }

        int restored = 0;
        for (var adj : adjustments) {
            var price = pricesByUuid.get(adj.getTargetRowId());
            if (price == null || !"close_price".equals(adj.getFieldName())) {
                // Price row may have been deleted between apply and unapply — non-fatal.
                log.warn("Price row for split adjustment {} not found; skipping restore", adj.getId());
                continue;
            }
            price.setClosePrice(adj.getOldValue().setScale(4, VALUE_ROUNDING));
            restored++;
        }
        return restored;
    }

    private static UUID priceUuid(String symbol, LocalDate date) {
        return UUID.nameUUIDFromBytes(("price:" + symbol + ":" + date).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * List splits affecting the given tenant — i.e. splits whose symbol the
     * tenant has at least one transaction in. Optionally narrowed by symbol
     * and date range.
     */
    @Transactional(readOnly = true)
    public List<StockSplitEntity> listForTenant(UUID tenantId, String symbol, LocalDate from, LocalDate to) {
        var symbols = transactionRepository.findByTenant_Id(tenantId).stream()
                .map(TransactionEntity::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (symbols.isEmpty()) {
            return List.of();
        }
        var fromDate = from != null ? from : LocalDate.of(1900, 1, 1);
        var toDate = to != null ? to : LocalDate.of(2999, 12, 31);
        var splits = stockSplitRepository.findBySymbolsInAndDateRange(symbols, fromDate, toDate);
        if (symbol == null || symbol.isBlank()) {
            return splits;
        }
        return splits.stream().filter(s -> symbol.equalsIgnoreCase(s.getSymbol())).toList();
    }

    private static void validateRatio(int numerator, int denominator) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("split ratio must be positive: " + numerator + ":" + denominator);
        }
    }
}
