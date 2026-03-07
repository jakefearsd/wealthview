package com.wealthview.core.transaction;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.holding.HoldingsComputationService;
import com.wealthview.core.transaction.dto.TransactionRequest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private HoldingsComputationService holdingsComputationService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TransactionService transactionService;

    private TenantEntity tenant;
    private AccountEntity account;
    private UUID tenantId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
    }

    @Test
    void create_buyTransaction_triggersRecompute() {
        var request = new TransactionRequest(LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = transactionService.create(tenantId, accountId, request);

        assertThat(result.symbol()).isEqualTo("AAPL");
        verify(holdingsComputationService).recomputeForAccountAndSymbol(
                eq(account), any(), eq("AAPL"));
    }

    @Test
    void create_depositTransaction_triggersRecomputeWithNull() {
        var request = new TransactionRequest(LocalDate.now(), "deposit", null,
                null, new BigDecimal("5000"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        transactionService.create(tenantId, accountId, request);

        verify(holdingsComputationService).recomputeForAccountAndSymbol(
                eq(account), any(), eq(null));
    }

    @Test
    void delete_existingTransaction_recomputesHoldings() {
        var txn = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(transactionRepository.findByIdAndTenant_Id(any(), eq(tenantId)))
                .thenReturn(Optional.of(txn));

        transactionService.delete(tenantId, UUID.randomUUID());

        verify(transactionRepository).delete(txn);
        verify(holdingsComputationService).recomputeForAccountAndSymbol(
                eq(account), eq(tenant), eq("AAPL"));
    }

    @Test
    void delete_nonExistent_throwsNotFound() {
        var txnId = UUID.randomUUID();
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.delete(tenantId, txnId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void update_existingTransaction_updatesFieldsAndRecomputes() {
        var txnId = UUID.randomUUID();
        var txn = new TransactionEntity(account, tenant, LocalDate.of(2025, 1, 10), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId))
                .thenReturn(Optional.of(txn));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new TransactionRequest(LocalDate.of(2025, 2, 1), "sell", "AAPL",
                new BigDecimal("5"), new BigDecimal("800"));

        var result = transactionService.update(tenantId, txnId, request);

        assertThat(result.type()).isEqualTo("sell");
        assertThat(result.quantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("800"));
        verify(holdingsComputationService).recomputeForAccountAndSymbol(eq(account), eq(tenant), eq("AAPL"));
    }

    @Test
    void update_symbolChanged_recomputesBothSymbols() {
        var txnId = UUID.randomUUID();
        var txn = new TransactionEntity(account, tenant, LocalDate.of(2025, 1, 10), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId))
                .thenReturn(Optional.of(txn));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new TransactionRequest(LocalDate.of(2025, 2, 1), "buy", "GOOG",
                new BigDecimal("5"), new BigDecimal("800"));

        transactionService.update(tenantId, txnId, request);

        verify(holdingsComputationService).recomputeForAccountAndSymbol(eq(account), eq(tenant), eq("GOOG"));
        verify(holdingsComputationService).recomputeForAccountAndSymbol(eq(account), eq(tenant), eq("AAPL"));
    }

    @Test
    void update_symbolUnchanged_recomputesOnlyOnce() {
        var txnId = UUID.randomUUID();
        var txn = new TransactionEntity(account, tenant, LocalDate.of(2025, 1, 10), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId))
                .thenReturn(Optional.of(txn));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new TransactionRequest(LocalDate.of(2025, 2, 1), "buy", "AAPL",
                new BigDecimal("20"), new BigDecimal("3000"));

        transactionService.update(tenantId, txnId, request);

        verify(holdingsComputationService, times(1)).recomputeForAccountAndSymbol(
                eq(account), eq(tenant), eq("AAPL"));
    }

    @Test
    void update_notFound_throwsEntityNotFoundException() {
        var txnId = UUID.randomUUID();
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId))
                .thenReturn(Optional.empty());

        var request = new TransactionRequest(LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));

        assertThatThrownBy(() -> transactionService.update(tenantId, txnId, request))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
