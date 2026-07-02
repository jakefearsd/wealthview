package com.wealthview.core.split;

import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.wealthview.persistence.entity.StockSplitAdjustmentEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.StockSplitAdjustmentRepository;
import com.wealthview.persistence.repository.StockSplitRepository;

/**
 * Adjusts a transaction that is created <i>after</i> the splits affecting it
 * were already applied, so holdings are correct regardless of import order.
 *
 * <p>Maintains the invariant that every stored transaction is split-adjusted.
 * One {@code stock_split_adjustment} row is written per split, oldest first,
 * so {@link StockSplitService#unapplySplit} reverses these transactions too,
 * and per-split unapply composes correctly across multiple splits.
 */
@Component
public class SplitAdjustmentApplier {

    private static final Logger log = LoggerFactory.getLogger(SplitAdjustmentApplier.class);
    private static final int VALUE_SCALE = 8;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final StockSplitRepository stockSplitRepository;
    private final StockSplitAdjustmentRepository adjustmentRepository;

    public SplitAdjustmentApplier(StockSplitRepository stockSplitRepository,
                                  StockSplitAdjustmentRepository adjustmentRepository) {
        this.stockSplitRepository = stockSplitRepository;
        this.adjustmentRepository = adjustmentRepository;
    }

    /**
     * Fold every applied split with an effective date on/after the transaction
     * date over the transaction's quantity. No-op when the transaction has no
     * symbol or no positive quantity, or when no such splits exist.
     */
    public void adjustNewTransaction(TransactionEntity txn) {
        var symbol = txn.getSymbol();
        var quantity = txn.getQuantity();
        if (symbol == null || symbol.isBlank() || quantity == null || quantity.signum() == 0) {
            return;
        }
        var tenantId = txn.getTenantId();
        var txnId = txn.getId();
        var splits = stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc(symbol, txn.getDate());
        if (splits.isEmpty()) {
            return;
        }
        var running = quantity;
        for (var split : splits) {
            var next = SplitMath.adjustShares(running, split.getNumerator(), split.getDenominator());
            adjustmentRepository.save(new StockSplitAdjustmentEntity(
                    split, tenantId, "transactions", txnId, "quantity",
                    running.setScale(VALUE_SCALE, ROUNDING),
                    next.setScale(VALUE_SCALE, ROUNDING)));
            running = next;
        }
        txn.setQuantity(running);
        log.info("Adjusted late-arriving transaction {} for {} across {} split(s)",
                txnId, symbol, splits.size());
    }
}
