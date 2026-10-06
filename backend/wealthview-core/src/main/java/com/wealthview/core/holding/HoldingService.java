package com.wealthview.core.holding;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.common.Entities;
import com.wealthview.core.common.Symbols;
import com.wealthview.core.holding.dto.HoldingRequest;
import com.wealthview.core.holding.dto.HoldingResponse;
import com.wealthview.core.price.LatestPriceLookup;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;

@Service
public class HoldingService {

    private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

    /** Stable listing order: without it row order follows the heap and jumps after every update. */
    private static final Comparator<HoldingEntity> BY_SYMBOL = Comparator.comparing(HoldingEntity::getSymbol);

    private final HoldingRepository holdingRepository;
    private final AccountRepository accountRepository;
    private final LatestPriceLookup latestPriceLookup;
    private final ApplicationEventPublisher eventPublisher;

    public HoldingService(HoldingRepository holdingRepository, AccountRepository accountRepository,
                          LatestPriceLookup latestPriceLookup, ApplicationEventPublisher eventPublisher) {
        this.holdingRepository = holdingRepository;
        this.accountRepository = accountRepository;
        this.latestPriceLookup = latestPriceLookup;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> listByAccount(UUID tenantId, UUID accountId) {
        var holdings = holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId);
        if (holdings.isEmpty()) {
            return List.of();
        }

        var symbols = holdings.stream().map(HoldingEntity::getSymbol).distinct().toList();
        var prices = latestPriceLookup.latestFor(symbols);

        return holdings.stream()
                .sorted(BY_SYMBOL)
                .map(h -> HoldingResponse.from(h, prices.get(h.getSymbol())))
                .toList();
    }

    @Transactional(readOnly = true)
    public HoldingResponse getById(UUID tenantId, UUID holdingId) {
        return holdingRepository.findByIdAndTenant_Id(holdingId, tenantId)
                .map(HoldingResponse::from)
                .orElseThrow(Entities.notFound("Holding"));
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> listByTenant(UUID tenantId) {
        return holdingRepository.findByTenant_Id(tenantId).stream()
                .sorted(BY_SYMBOL)
                .map(HoldingResponse::from)
                .toList();
    }

    @CacheEvict(value = "accountBalances", key = "#tenantId")
    @Transactional
    public HoldingResponse createManual(UUID tenantId, HoldingRequest request) {
        var account = accountRepository.findByTenant_IdAndId(tenantId, request.accountId())
                .orElseThrow(Entities.notFound("Account"));

        var symbol = Symbols.normalize(request.symbol());
        var holding = new HoldingEntity(account, account.getTenant(),
                symbol, request.quantity(), request.costBasis());
        holding.setManualOverride(true);
        holding.setAsOfDate(LocalDate.now());
        holding = holdingRepository.save(holding);

        log.info("Manual holding created for account {} symbol {}", request.accountId(), symbol);
        eventPublisher.publishEvent(new AuditEvent(tenantId, null, "CREATE", "holding",
                holding.getId(), Map.of("symbol", symbol)));
        return HoldingResponse.from(holding);
    }

    @CacheEvict(value = "accountBalances", key = "#tenantId")
    @Transactional
    public HoldingResponse update(UUID tenantId, UUID holdingId, HoldingRequest request) {
        var holding = holdingRepository.findByIdAndTenant_Id(holdingId, tenantId)
                .orElseThrow(Entities.notFound("Holding"));

        holding.setQuantity(request.quantity());
        holding.setCostBasis(request.costBasis());
        holding.setManualOverride(true);
        holding.setAsOfDate(LocalDate.now());
        holding = holdingRepository.save(holding);
        log.info("Manual holding updated for account {} symbol {} ({})", holding.getAccount().getId(),
                holding.getSymbol(), holdingId);

        eventPublisher.publishEvent(new AuditEvent(tenantId, null, "UPDATE", "holding",
                holdingId, Map.of("symbol", holding.getSymbol())));
        return HoldingResponse.from(holding);
    }
}
