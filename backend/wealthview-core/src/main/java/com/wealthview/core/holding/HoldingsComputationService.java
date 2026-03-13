package com.wealthview.core.holding;

import com.wealthview.core.pricefeed.NewHoldingCreatedEvent;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.persistence.entity.TransactionEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

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

    @Transactional
    public void recomputeForAccountAndSymbol(AccountEntity account, TenantEntity tenant, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        log.debug("Recomputing holdings for account {} symbol {}", account.getId(), symbol);

        var existingHolding = holdingRepository.findByAccount_IdAndSymbol(account.getId(), symbol);

        if (existingHolding.filter(HoldingEntity::isManualOverride).isPresent()) {
            log.warn("Skipping recomputation for account {} symbol {} — manual override exists",
                    account.getId(), symbol);
            return;
        }

        var transactions = transactionRepository.findByAccount_IdAndSymbol(account.getId(), symbol);
        var aggregated = aggregateTransactions(transactions);
        var netQuantity = aggregated[0];
        var totalCost = aggregated[1];

        saveOrUpdateHolding(existingHolding, account, tenant, symbol, netQuantity, totalCost);
    }

    private BigDecimal[] aggregateTransactions(List<TransactionEntity> transactions) {
        var netQuantity = BigDecimal.ZERO;
        var totalCost = BigDecimal.ZERO;

        for (var txn : transactions) {
            switch (txn.getType()) {
                case "buy", "opening_balance" -> {
                    netQuantity = netQuantity.add(txn.getQuantity());
                    totalCost = totalCost.add(txn.getAmount());
                }
                case "sell" -> {
                    if (netQuantity.compareTo(BigDecimal.ZERO) > 0) {
                        var avgCost = totalCost.divide(netQuantity, 4, RoundingMode.HALF_UP);
                        netQuantity = netQuantity.subtract(txn.getQuantity());
                        totalCost = netQuantity.multiply(avgCost).setScale(4, RoundingMode.HALF_UP);
                    }
                }
                default -> {
                    // dividend, deposit, withdrawal don't affect quantity
                }
            }
        }

        if (netQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            netQuantity = BigDecimal.ZERO;
            totalCost = BigDecimal.ZERO;
        }
        return new BigDecimal[]{netQuantity, totalCost};
    }

    private void saveOrUpdateHolding(java.util.Optional<HoldingEntity> existingHolding,
                                      AccountEntity account, TenantEntity tenant,
                                      String symbol, BigDecimal netQuantity, BigDecimal totalCost) {
        if (existingHolding.isPresent()) {
            var holding = existingHolding.orElseThrow();
            holding.setQuantity(netQuantity);
            holding.setCostBasis(totalCost);
            holding.setAsOfDate(LocalDate.now());
            holding.setUpdatedAt(OffsetDateTime.now());
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
