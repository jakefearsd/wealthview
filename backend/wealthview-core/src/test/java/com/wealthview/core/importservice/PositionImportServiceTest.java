package com.wealthview.core.importservice;

import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.core.importservice.dto.ImportJobResponse;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.ImportJobEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.ImportJobRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PositionImportServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private ImportService importService;

    private PositionImportService positionImportService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        positionImportService = new PositionImportService(
                accountRepository, transactionRepository, holdingRepository,
                entityManager, importService);
    }

    private void setupAccountMock() {
        var tenant = new TenantEntity("Test Tenant");
        setField(tenant, "id", tenantId);
        var account = new AccountEntity(tenant, "Test Account", "brokerage", "Test Bank");
        setField(account, "id", accountId);
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(account));
    }

    @Test
    void importPositions_deletesExistingTransactionsAndHoldings() throws IOException {
        setupAccountMock();

        var transactions = List.of(
                new ParsedTransaction(LocalDate.of(2026, 3, 5), "opening_balance", "AMZN",
                        new BigDecimal("1100"), new BigDecimal("112324.74")));
        var parseResult = new CsvParseResult(transactions, List.of());
        when(importService.resolveParser("generic")).thenReturn(is -> parseResult);

        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "positions", "completed",
                1, 1, 0, null, null);
        when(importService.processImport(tenantId, accountId, parseResult, "positions"))
                .thenReturn(jobResponse);

        positionImportService.importPositions(tenantId, accountId,
                new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), "generic");

        verify(transactionRepository).deleteByAccount_IdAndTenant_Id(accountId, tenantId);
        verify(holdingRepository).deleteByAccount_IdAndTenant_Id(accountId, tenantId);
        verify(entityManager).flush();
    }

    @Test
    void importPositions_delegatesToImportServiceForProcessing() throws IOException {
        setupAccountMock();

        var transactions = List.of(
                new ParsedTransaction(LocalDate.of(2026, 3, 5), "opening_balance", "AMZN",
                        new BigDecimal("1100"), new BigDecimal("112324.74")),
                new ParsedTransaction(LocalDate.of(2026, 3, 5), "deposit", null,
                        null, new BigDecimal("704.82")));
        var parseResult = new CsvParseResult(transactions, List.of());
        when(importService.resolveParser("fidelityPositions")).thenReturn(is -> parseResult);

        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "positions", "completed",
                2, 2, 0, null, null);
        when(importService.processImport(tenantId, accountId, parseResult, "positions"))
                .thenReturn(jobResponse);

        var result = positionImportService.importPositions(tenantId, accountId,
                new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), "fidelityPositions");

        assertThat(result.successfulRows()).isEqualTo(2);
        assertThat(result.source()).isEqualTo("positions");
        verify(importService).processImport(tenantId, accountId, parseResult, "positions");
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
