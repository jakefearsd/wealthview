package com.wealthview.core.holding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.auth.CrossTenant;
import com.wealthview.core.common.Money;
import com.wealthview.core.pricefeed.NewHoldingCreatedEvent;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.TransactionRepository;

@Service
public class HoldingsComputationService {

    private static final Logger log = LoggerFactory.getLogger(HoldingsComputationService.class);

    private final TransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final ApplicationEventPublisher eventPublisher;

    public HoldingsComputationService(TransactionRepository transactionRepository,
                                      HoldingRepository holdingRepository,
                                      ApplicationEventPublisher eventPublisher) {
        this.transactionRepository = transactionRepository;
        this.holdingRepository = holdingRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Recompute holdings for every account belonging to {@code tenantId} that
     * has at least one transaction in {@code symbol}. Used by the stock-split
     * apply/unapply flow, where a single split touches every account holding
     * the symbol within a tenant.
     *
     * <p>{@code @CrossTenant}: called from the {@code @CrossTenant} split
     * apply/unapply flow once per affected tenant, iterating tenants that are
     * unrelated to the calling thread's Authentication. As a separate
     * {@code @Transactional} bean method it gets its own TenantFilterAspect
     * pass, which would otherwise re-enable the {@code tenantFilter} from a
     * tenant-scoped (or stale) principal and silently skip the recompute.
     * Safe: tenant scoping is enforced by the explicit {@code tenantId}
     * parameter on every query, and nothing is returned to the caller.
     */
    @CrossTenant
    @Transactional
    public void recomputeAllForTenantAndSymbol(java.util.UUID tenantId, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        var txns = transactionRepository.findByTenant_IdAndSymbol(tenantId, symbol);
        var seenAccountIds = new java.util.HashSet<java.util.UUID>();
        for (var txn : txns) {
            if (seenAccountIds.add(txn.getAccount().getId())) {
                recomputeForAccountAndSymbol(txn.getAccount(), txn.getTenant(), symbol);
            }
        }
    }

    @CacheEvict(value = "accountBalances", key = "#tenant.id")
    @Transactional
    public void recomputeForAccountAndSymbol(AccountEntity account, TenantEntity tenant, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        log.info("Recomputing holdings for account {} symbol {}", account.getId(), symbol);

        var existingHolding = holdingRepository.findByAccount_IdAndSymbol(account.getId(), symbol);

        if (existingHolding.filter(HoldingEntity::isManualOverride).isPresent()) {
            log.warn("Skipping recomputation for account {} symbol {} — manual override exists",
                    account.getId(), symbol);
            return;
        }

        var transactions = transactionRepository.findByAccount_IdAndSymbol(account.getId(), symbol);
        var position = aggregateTransactions(transactions);

        saveOrUpdateHolding(existingHolding, account, tenant, symbol,
                position.netQuantity(), position.totalCost());
    }

    /** The net position implied by a symbol's transaction history: quantity held and its cost basis. */
    private record AggregatedPosition(BigDecimal netQuantity, BigDecimal totalCost) {
        private static final AggregatedPosition FLAT =
                new AggregatedPosition(BigDecimal.ZERO, BigDecimal.ZERO);

        private AggregatedPosition acquire(BigDecimal quantity, BigDecimal cost) {
            return new AggregatedPosition(netQuantity.add(quantity), totalCost.add(cost));
        }

        /** Average-cost disposal: the remaining basis follows the remaining share count. */
        private AggregatedPosition dispose(BigDecimal quantity) {
            if (netQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                return this;
            }
            var avgCost = totalCost.divide(netQuantity, 4, RoundingMode.HALF_UP);
            var remaining = netQuantity.subtract(quantity);
            return new AggregatedPosition(remaining,
                    remaining.multiply(avgCost).setScale(Money.SCALE, Money.ROUNDING));
        }
    }

    private AggregatedPosition aggregateTransactions(List<TransactionEntity> transactions) {
        var position = AggregatedPosition.FLAT;

        for (var txn : transactions) {
            // Exhaustive over TransactionType with no default branch: adding a constant to the
            // enum becomes a compile error here instead of silently falling into "ignore".
            position = switch (txn.getType()) {
                case BUY, OPENING_BALANCE -> position.acquire(txn.getQuantity(), txn.getAmount());
                case SELL -> position.dispose(txn.getQuantity());
                // Cash-only movements change the account balance, never the share count.
                case DIVIDEND, DEPOSIT, WITHDRAWAL -> position;
            };
        }

        if (position.netQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return AggregatedPosition.FLAT;
        }
        return position;
    }

    private void saveOrUpdateHolding(java.util.Optional<HoldingEntity> existingHolding,
                                      AccountEntity account, TenantEntity tenant,
                                      String symbol, BigDecimal netQuantity, BigDecimal totalCost) {
        if (existingHolding.isPresent()) {
            var holding = existingHolding.orElseThrow();
            holding.setQuantity(netQuantity);
            holding.setCostBasis(totalCost);
            holding.setAsOfDate(LocalDate.now());
            applyMoneyMarketFlag(holding, symbol);
            holdingRepository.save(holding);
            log.info("Holdings updated for account {} symbol {}: qty={} cost={}",
                    account.getId(), symbol, netQuantity, totalCost);
        } else if (netQuantity.compareTo(BigDecimal.ZERO) > 0) {
            var holding = new HoldingEntity(account, tenant, symbol, netQuantity, totalCost);
            applyMoneyMarketFlag(holding, symbol);
            holdingRepository.save(holding);
            eventPublisher.publishEvent(new NewHoldingCreatedEvent(symbol));
            log.info("Holdings created for account {} symbol {}: qty={} cost={}",
                    account.getId(), symbol, netQuantity, totalCost);
        }
    }

    private void applyMoneyMarketFlag(HoldingEntity holding, String symbol) {
        if (MoneyMarketDetector.isMoneyMarket(symbol)) {
            holding.setMoneyMarket(true);
            holding.setMoneyMarketRate(MoneyMarketDetector.defaultRate());
        }
    }
}
