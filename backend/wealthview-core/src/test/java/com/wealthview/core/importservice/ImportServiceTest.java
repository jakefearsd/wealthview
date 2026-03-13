package com.wealthview.core.importservice;

import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import com.wealthview.core.transaction.TransactionService;
import com.wealthview.core.transaction.dto.TransactionRequest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.ImportJobEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ImportJobRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportServiceTest {

    @Mock
    private ImportJobRepository importJobRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private TransactionService transactionService;
    @Mock
    private CsvParser csvParser;

    private ImportService importService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        importService = new ImportService(
                importJobRepository, accountRepository, transactionRepository,
                transactionService, csvParser, Map.of());
    }

    private void setupAccountAndJobMocks() {
        var tenant = new TenantEntity("Test Tenant");
        setField(tenant, "id", tenantId);
        var account = new AccountEntity(tenant, "Test Account", "brokerage", "Test Bank");
        setField(account, "id", accountId);
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
        when(importJobRepository.save(any(ImportJobEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void processCsvImport_newTransactions_createsAllWithHashes() {
        setupAccountAndJobMocks();
        when(transactionRepository.existsByTenant_IdAndAccount_IdAndImportHash(
                any(), any(), anyString())).thenReturn(false);

        var transactions = List.of(
                new ParsedTransaction(LocalDate.of(2024, 1, 15), "buy", "AAPL",
                        new BigDecimal("10"), new BigDecimal("1500")),
                new ParsedTransaction(LocalDate.of(2024, 1, 16), "sell", "GOOG",
                        new BigDecimal("5"), new BigDecimal("750")));
        var parseResult = new CsvParseResult(transactions, List.of());

        var result = importService.processCsvImport(tenantId, accountId, parseResult);

        verify(transactionService, times(2)).createWithHash(
                eq(tenantId), eq(accountId), any(TransactionRequest.class), anyString());
        assertThat(result.successfulRows()).isEqualTo(2);
        assertThat(result.failedRows()).isEqualTo(0);
    }

    @Test
    void processCsvImport_duplicateTransactions_skipsAll() {
        setupAccountAndJobMocks();
        when(transactionRepository.existsByTenant_IdAndAccount_IdAndImportHash(
                any(), any(), anyString())).thenReturn(true);

        var transactions = List.of(
                new ParsedTransaction(LocalDate.of(2024, 1, 15), "buy", "AAPL",
                        new BigDecimal("10"), new BigDecimal("1500")));
        var parseResult = new CsvParseResult(transactions, List.of());

        var result = importService.processCsvImport(tenantId, accountId, parseResult);

        verify(transactionService, never()).createWithHash(
                any(), any(), any(TransactionRequest.class), anyString());
        assertThat(result.successfulRows()).isEqualTo(0);
        assertThat(result.errorMessage()).contains("duplicate transactions skipped");
    }

    @Test
    void processCsvImport_mixOfNewAndDuplicate_onlyCreatesNew() {
        setupAccountAndJobMocks();

        var buyHash = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        var sellHash = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 16), "sell", "GOOG",
                new BigDecimal("5"), new BigDecimal("750"));

        when(transactionRepository.existsByTenant_IdAndAccount_IdAndImportHash(
                tenantId, accountId, buyHash)).thenReturn(true);
        when(transactionRepository.existsByTenant_IdAndAccount_IdAndImportHash(
                tenantId, accountId, sellHash)).thenReturn(false);

        var transactions = List.of(
                new ParsedTransaction(LocalDate.of(2024, 1, 15), "buy", "AAPL",
                        new BigDecimal("10"), new BigDecimal("1500")),
                new ParsedTransaction(LocalDate.of(2024, 1, 16), "sell", "GOOG",
                        new BigDecimal("5"), new BigDecimal("750")));
        var parseResult = new CsvParseResult(transactions, List.of());

        var result = importService.processCsvImport(tenantId, accountId, parseResult);

        verify(transactionService, times(1)).createWithHash(
                eq(tenantId), eq(accountId), any(TransactionRequest.class), eq(sellHash));
        assertThat(result.successfulRows()).isEqualTo(1);
        assertThat(result.errorMessage()).contains("1 duplicate transactions skipped");
    }

    @Test
    void processCsvImport_hashPassedToCreateWithHash() {
        setupAccountAndJobMocks();
        when(transactionRepository.existsByTenant_IdAndAccount_IdAndImportHash(
                any(), any(), anyString())).thenReturn(false);

        var transactions = List.of(
                new ParsedTransaction(LocalDate.of(2024, 1, 15), "buy", "AAPL",
                        new BigDecimal("10"), new BigDecimal("1500")));
        var parseResult = new CsvParseResult(transactions, List.of());

        importService.processCsvImport(tenantId, accountId, parseResult);

        var hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(transactionService).createWithHash(
                eq(tenantId), eq(accountId), any(TransactionRequest.class), hashCaptor.capture());

        var expectedHash = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        assertThat(hashCaptor.getValue()).isEqualTo(expectedHash);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
