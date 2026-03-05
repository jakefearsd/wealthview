package com.wealthview.core.transaction;

import com.wealthview.core.common.PageResponse;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.holding.HoldingsComputationService;
import com.wealthview.core.transaction.dto.TransactionRequest;
import com.wealthview.core.transaction.dto.TransactionResponse;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final HoldingsComputationService holdingsComputationService;

    public TransactionService(TransactionRepository transactionRepository,
                              AccountRepository accountRepository,
                              HoldingsComputationService holdingsComputationService) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.holdingsComputationService = holdingsComputationService;
    }

    @Transactional
    public TransactionResponse create(UUID tenantId, UUID accountId, TransactionRequest request) {
        var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        var txn = new TransactionEntity(account, account.getTenant(), request.date(),
                request.type(), request.symbol(), request.quantity(), request.amount());
        txn = transactionRepository.save(txn);

        holdingsComputationService.recomputeForAccountAndSymbol(
                account, account.getTenant(), request.symbol());

        log.info("Transaction {} created for account {}", txn.getId(), accountId);
        return TransactionResponse.from(txn);
    }

    @Transactional
    public TransactionResponse createWithHash(UUID tenantId, UUID accountId,
                                              TransactionRequest request, String importHash) {
        var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        var txn = new TransactionEntity(account, account.getTenant(), request.date(),
                request.type(), request.symbol(), request.quantity(), request.amount());
        txn.setImportHash(importHash);
        txn = transactionRepository.save(txn);

        holdingsComputationService.recomputeForAccountAndSymbol(
                account, account.getTenant(), request.symbol());

        log.info("Transaction {} created for account {} with import hash", txn.getId(), accountId);
        return TransactionResponse.from(txn);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> listByAccount(UUID tenantId, UUID accountId, Pageable pageable) {
        var page = transactionRepository.findByAccount_IdAndTenant_Id(accountId, tenantId, pageable);
        return PageResponse.from(page, TransactionResponse::from);
    }

    @Transactional
    public TransactionResponse update(UUID tenantId, UUID transactionId, TransactionRequest request) {
        var txn = transactionRepository.findByIdAndTenant_Id(transactionId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Transaction not found"));

        var oldSymbol = txn.getSymbol();
        txn.setDate(request.date());
        txn.setType(request.type());
        txn.setSymbol(request.symbol());
        txn.setQuantity(request.quantity());
        txn.setAmount(request.amount());
        txn.setUpdatedAt(OffsetDateTime.now());
        txn = transactionRepository.save(txn);

        var account = txn.getAccount();
        var tenant = txn.getTenant();
        holdingsComputationService.recomputeForAccountAndSymbol(account, tenant, request.symbol());
        if (oldSymbol != null && !oldSymbol.equals(request.symbol())) {
            holdingsComputationService.recomputeForAccountAndSymbol(account, tenant, oldSymbol);
        }

        return TransactionResponse.from(txn);
    }

    @Transactional
    public void delete(UUID tenantId, UUID transactionId) {
        var txn = transactionRepository.findByIdAndTenant_Id(transactionId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Transaction not found"));

        var account = txn.getAccount();
        var tenant = txn.getTenant();
        var symbol = txn.getSymbol();

        transactionRepository.delete(txn);

        holdingsComputationService.recomputeForAccountAndSymbol(account, tenant, symbol);
        log.info("Transaction {} deleted", transactionId);
    }
}
