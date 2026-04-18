package com.wealthview.core.transaction;

import com.wealthview.core.audit.AuditEvent;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void create_accountNotFound_throwsAndSavesNothing() {
        var request = new TransactionRequest(LocalDate.now(), "buy", "AAPL",
                new BigDecimal("1"), new BigDecimal("100"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create(tenantId, accountId, request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Account not found");

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(holdingsComputationService, eventPublisher);
    }

    @Test
    void create_buyTransaction_publishesAuditEventWithSymbolAndType() {
        var request = new TransactionRequest(LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        transactionService.create(tenantId, accountId, request);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.entityType()).isEqualTo("transaction");
        assertThat(event.details())
                .containsEntry("type", "buy")
                .containsEntry("symbol", "AAPL");
    }

    @Test
    void create_depositTransaction_auditDetailsOmitNullSymbol() {
        var request = new TransactionRequest(LocalDate.now(), "deposit", null,
                null, new BigDecimal("5000"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        transactionService.create(tenantId, accountId, request);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().details())
                .containsEntry("type", "deposit")
                .doesNotContainKey("symbol");
    }

    @Test
    void createWithHash_persistsImportHashAndRecomputes() {
        var request = new TransactionRequest(LocalDate.now(), "buy", "GOOG",
                new BigDecimal("3"), new BigDecimal("900"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = transactionService.createWithHash(tenantId, accountId, request, "hash-abc");

        assertThat(result.symbol()).isEqualTo("GOOG");
        var captor = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getImportHash()).isEqualTo("hash-abc");
        verify(holdingsComputationService).recomputeForAccountAndSymbol(eq(account), any(), eq("GOOG"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void createWithHash_accountNotFound_throws() {
        var request = new TransactionRequest(LocalDate.now(), "buy", "AAPL",
                new BigDecimal("1"), new BigDecimal("100"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.createWithHash(tenantId, accountId, request, "h"))
                .isInstanceOf(EntityNotFoundException.class);

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(holdingsComputationService);
    }

    @Test
    void createWithHashNoRecompute_persistsHashAndSkipsRecompute() {
        var request = new TransactionRequest(LocalDate.now(), "buy", "GOOG",
                new BigDecimal("3"), new BigDecimal("900"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = transactionService.createWithHashNoRecompute(tenantId, accountId, request, "hash-xyz");

        assertThat(result.symbol()).isEqualTo("GOOG");
        var captor = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getImportHash()).isEqualTo("hash-xyz");
        verifyNoInteractions(holdingsComputationService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void createWithHashNoRecompute_accountNotFound_throws() {
        var request = new TransactionRequest(LocalDate.now(), "buy", "AAPL",
                new BigDecimal("1"), new BigDecimal("100"));
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.createWithHashNoRecompute(
                tenantId, accountId, request, "h"))
                .isInstanceOf(EntityNotFoundException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void listByAccount_mapsPageToResponses() {
        var txn = new TransactionEntity(account, tenant, LocalDate.of(2025, 1, 1), "buy", "AAPL",
                new BigDecimal("1"), new BigDecimal("100"));
        var pageable = PageRequest.of(0, 10);
        when(transactionRepository.findByAccount_IdAndTenant_Id(accountId, tenantId, pageable))
                .thenReturn(new PageImpl<>(List.of(txn), pageable, 1));

        var result = transactionService.listByAccount(tenantId, accountId, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.total()).isEqualTo(1);
    }

    @Test
    void listByAccountAndSymbol_filtersBySymbolAndMapsPage() {
        var txn = new TransactionEntity(account, tenant, LocalDate.of(2025, 1, 1), "sell", "MSFT",
                new BigDecimal("2"), new BigDecimal("500"));
        var pageable = PageRequest.of(0, 5);
        when(transactionRepository.findByAccount_IdAndTenant_IdAndSymbol(accountId, tenantId, "MSFT", pageable))
                .thenReturn(new PageImpl<>(List.of(txn), pageable, 1));

        var result = transactionService.listByAccountAndSymbol(tenantId, accountId, "MSFT", pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).symbol()).isEqualTo("MSFT");
    }

    @Test
    void update_oldSymbolNull_recomputesOnlyNewSymbol() {
        var txnId = UUID.randomUUID();
        var txn = new TransactionEntity(account, tenant, LocalDate.of(2025, 1, 1), "deposit", null,
                null, new BigDecimal("500"));
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId)).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var request = new TransactionRequest(LocalDate.of(2025, 2, 1), "buy", "AAPL",
                new BigDecimal("1"), new BigDecimal("150"));

        transactionService.update(tenantId, txnId, request);

        verify(holdingsComputationService, times(1))
                .recomputeForAccountAndSymbol(eq(account), eq(tenant), eq("AAPL"));
    }

    @Test
    void update_publishesUpdateAuditEventWithSymbol() {
        var txnId = UUID.randomUUID();
        var txn = new TransactionEntity(account, tenant, LocalDate.of(2025, 1, 1), "buy", "AAPL",
                new BigDecimal("1"), new BigDecimal("100"));
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId)).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var request = new TransactionRequest(LocalDate.of(2025, 2, 1), "sell", "AAPL",
                new BigDecimal("1"), new BigDecimal("120"));

        transactionService.update(tenantId, txnId, request);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("UPDATE");
        assertThat(captor.getValue().details())
                .containsEntry("type", "sell")
                .containsEntry("symbol", "AAPL");
    }

    @Test
    void delete_publishesDeleteAuditEventWithSymbol() {
        var txnId = UUID.randomUUID();
        var txn = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("1"), new BigDecimal("100"));
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId)).thenReturn(Optional.of(txn));

        transactionService.delete(tenantId, txnId);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("DELETE");
        assertThat(captor.getValue().details()).containsEntry("symbol", "AAPL");
    }

    @Test
    void delete_nullSymbol_publishesEmptyDetailsMap() {
        var txnId = UUID.randomUUID();
        var txn = new TransactionEntity(account, tenant, LocalDate.now(), "deposit", null,
                null, new BigDecimal("250"));
        when(transactionRepository.findByIdAndTenant_Id(txnId, tenantId)).thenReturn(Optional.of(txn));

        transactionService.delete(tenantId, txnId);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().details()).isEmpty();
        verify(holdingsComputationService).recomputeForAccountAndSymbol(eq(account), eq(tenant), eq(null));
    }
}
