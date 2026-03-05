package com.wealthview.core.holding;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.holding.dto.HoldingRequest;
import com.wealthview.core.holding.dto.HoldingResponse;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class HoldingService {

    private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

    private final HoldingRepository holdingRepository;
    private final AccountRepository accountRepository;

    public HoldingService(HoldingRepository holdingRepository, AccountRepository accountRepository) {
        this.holdingRepository = holdingRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> listByAccount(UUID tenantId, UUID accountId) {
        return holdingRepository.findByAccountIdAndTenantId(accountId, tenantId).stream()
                .map(HoldingResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> listByTenant(UUID tenantId) {
        return holdingRepository.findByTenantId(tenantId).stream()
                .map(HoldingResponse::from)
                .toList();
    }

    @Transactional
    public HoldingResponse createManual(UUID tenantId, HoldingRequest request) {
        var account = accountRepository.findByTenantIdAndId(tenantId, request.accountId())
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        var holding = new HoldingEntity(account, account.getTenant(),
                request.symbol(), request.quantity(), request.costBasis());
        holding.setManualOverride(true);
        holding.setAsOfDate(LocalDate.now());
        holding = holdingRepository.save(holding);

        log.info("Manual holding created for account {} symbol {}", request.accountId(), request.symbol());
        return HoldingResponse.from(holding);
    }

    @Transactional
    public HoldingResponse update(UUID tenantId, UUID holdingId, HoldingRequest request) {
        var holding = holdingRepository.findByIdAndTenantId(holdingId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Holding not found"));

        holding.setQuantity(request.quantity());
        holding.setCostBasis(request.costBasis());
        holding.setManualOverride(true);
        holding.setAsOfDate(LocalDate.now());
        holding.setUpdatedAt(OffsetDateTime.now());
        holding = holdingRepository.save(holding);

        return HoldingResponse.from(holding);
    }
}
