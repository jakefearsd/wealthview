package com.wealthview.core.account;

import com.wealthview.core.account.dto.AccountRequest;
import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.common.PageResponse;
import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final TenantRepository tenantRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final PriceRepository priceRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AccountService(AccountRepository accountRepository, TenantRepository tenantRepository,
                          HoldingRepository holdingRepository, TransactionRepository transactionRepository,
                          PriceRepository priceRepository, ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.tenantRepository = tenantRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.priceRepository = priceRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AccountResponse create(UUID tenantId, AccountRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        var account = new AccountEntity(tenant, request.name(), request.type(), request.institution());
        account = accountRepository.save(account);
        log.info("Account {} created for tenant {}", account.getId(), tenantId);
        eventPublisher.publishEvent(new AuditEvent(tenantId, null, "CREATE", "account",
                account.getId(), Map.of("name", request.name(), "type", request.type())));
        return AccountResponse.from(account, BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> list(UUID tenantId, Pageable pageable) {
        var page = accountRepository.findByTenant_Id(tenantId, pageable);
        return PageResponse.from(page, account -> AccountResponse.from(account, computeBalance(account, tenantId)));
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID tenantId, UUID accountId) {
        var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));
        return AccountResponse.from(account, computeBalance(account, tenantId));
    }

    @Transactional
    public AccountResponse update(UUID tenantId, UUID accountId, AccountRequest request) {
        var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        account.setName(request.name());
        account.setType(request.type());
        account.setInstitution(request.institution());
        account.setUpdatedAt(OffsetDateTime.now());
        account = accountRepository.save(account);
        log.info("Account {} updated for tenant {}", accountId, tenantId);
        return AccountResponse.from(account, computeBalance(account, tenantId));
    }

    @Transactional
    public void delete(UUID tenantId, UUID accountId) {
        var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));
        accountRepository.delete(account);
        log.info("Account {} deleted for tenant {}", accountId, tenantId);
        eventPublisher.publishEvent(new AuditEvent(tenantId, null, "DELETE", "account",
                accountId, Map.of()));
    }

    public BigDecimal computeBalance(AccountEntity account, UUID tenantId) {
        if ("bank".equals(account.getType())) {
            return computeBankBalance(account, tenantId);
        }
        return computeInvestmentValue(account, tenantId);
    }

    private BigDecimal computeBankBalance(AccountEntity account, UUID tenantId) {
        var transactions = transactionRepository.findByAccount_IdAndTenant_Id(
                account.getId(), tenantId, Pageable.unpaged());
        var balance = BigDecimal.ZERO;
        for (var txn : transactions) {
            if ("deposit".equals(txn.getType())) {
                balance = balance.add(txn.getAmount());
            } else if ("withdrawal".equals(txn.getType())) {
                balance = balance.subtract(txn.getAmount());
            }
        }
        return balance;
    }

    private BigDecimal computeInvestmentValue(AccountEntity account, UUID tenantId) {
        var holdings = holdingRepository.findByAccount_IdAndTenant_Id(account.getId(), tenantId);
        var value = BigDecimal.ZERO;
        for (var holding : holdings) {
            value = value.add(getHoldingValue(holding));
        }
        return value;
    }

    private BigDecimal getHoldingValue(HoldingEntity holding) {
        return priceRepository.findFirstBySymbolOrderByDateDesc(holding.getSymbol())
                .map(price -> holding.getQuantity().multiply(price.getClosePrice())
                        .setScale(4, RoundingMode.HALF_UP))
                .orElse(holding.getCostBasis());
    }
}
