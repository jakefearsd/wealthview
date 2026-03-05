package com.wealthview.core.account;

import com.wealthview.core.account.dto.AccountRequest;
import com.wealthview.core.common.PageResponse;
import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final TenantRepository tenantRepository;

    public AccountService(AccountRepository accountRepository, TenantRepository tenantRepository) {
        this.accountRepository = accountRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public AccountResponse create(UUID tenantId, AccountRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found"));

        var account = new AccountEntity(tenant, request.name(), request.type(), request.institution());
        account = accountRepository.save(account);
        log.info("Account {} created for tenant {}", account.getId(), tenantId);
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> list(UUID tenantId, Pageable pageable) {
        var page = accountRepository.findByTenantId(tenantId, pageable);
        return PageResponse.from(page, AccountResponse::from);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID tenantId, UUID accountId) {
        var account = accountRepository.findByTenantIdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));
        return AccountResponse.from(account);
    }

    @Transactional
    public AccountResponse update(UUID tenantId, UUID accountId, AccountRequest request) {
        var account = accountRepository.findByTenantIdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        account.setName(request.name());
        account.setType(request.type());
        account.setInstitution(request.institution());
        account.setUpdatedAt(OffsetDateTime.now());
        account = accountRepository.save(account);
        return AccountResponse.from(account);
    }

    @Transactional
    public void delete(UUID tenantId, UUID accountId) {
        var account = accountRepository.findByTenantIdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));
        accountRepository.delete(account);
        log.info("Account {} deleted for tenant {}", accountId, tenantId);
    }
}
