package com.wealthview.core.holding;

import com.wealthview.core.pricefeed.NewHoldingCreatedEvent;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldingsComputationServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private HoldingsComputationService service;

    private TenantEntity tenant;
    private AccountEntity account;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        service = new HoldingsComputationService(transactionRepository, holdingRepository, eventPublisher);
        tenant = new TenantEntity("Test");
        account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        accountId = UUID.randomUUID();
    }

    @Test
    void recomputeHoldings_singleBuy_setsQuantityAndCostBasis() {
        var txn = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.0000"));

        when(holdingRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(List.of(txn));
        when(holdingRepository.save(any(HoldingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recomputeForAccountAndSymbol(account, tenant, "AAPL");

        var captor = ArgumentCaptor.forClass(HoldingEntity.class);
        verify(holdingRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("10");
        assertThat(captor.getValue().getCostBasis()).isEqualByComparingTo("1500.0000");
    }

    @Test
    void recomputeHoldings_buyThenSell_calculatesNetQuantity() {
        var buy = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.0000"));
        var sell = new TransactionEntity(account, tenant, LocalDate.now(), "sell", "AAPL",
                new BigDecimal("3"), new BigDecimal("600.0000"));

        when(holdingRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(List.of(buy, sell));
        when(holdingRepository.save(any(HoldingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recomputeForAccountAndSymbol(account, tenant, "AAPL");

        var captor = ArgumentCaptor.forClass(HoldingEntity.class);
        verify(holdingRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("7");
    }

    @Test
    void recomputeHoldings_multipleBuysAtDifferentPrices_calculatesAverageCostBasis() {
        var buy1 = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1000.0000"));
        var buy2 = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("2000.0000"));

        when(holdingRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(List.of(buy1, buy2));
        when(holdingRepository.save(any(HoldingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recomputeForAccountAndSymbol(account, tenant, "AAPL");

        var captor = ArgumentCaptor.forClass(HoldingEntity.class);
        verify(holdingRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("20");
        assertThat(captor.getValue().getCostBasis()).isEqualByComparingTo("3000.0000");
    }

    @Test
    void recomputeHoldings_allSold_setsQuantityToZero() {
        var buy = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.0000"));
        var sell = new TransactionEntity(account, tenant, LocalDate.now(), "sell", "AAPL",
                new BigDecimal("10"), new BigDecimal("1800.0000"));

        when(holdingRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(List.of(buy, sell));

        service.recomputeForAccountAndSymbol(account, tenant, "AAPL");

        verify(holdingRepository, never()).save(any());
    }

    @Test
    void recomputeHoldings_manualOverrideExists_logsWarningAndSkips() {
        var override = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("100"), new BigDecimal("15000"));
        override.setManualOverride(true);

        when(holdingRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(Optional.of(override));

        service.recomputeForAccountAndSymbol(account, tenant, "AAPL");

        verify(transactionRepository, never()).findByAccountIdAndSymbol(any(), any());
        verify(holdingRepository, never()).save(any());
    }

    @Test
    void recomputeHoldings_dividendTransaction_doesNotAffectQuantity() {
        var buy = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.0000"));
        var div = new TransactionEntity(account, tenant, LocalDate.now(), "dividend", "AAPL",
                null, new BigDecimal("50.0000"));

        when(holdingRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(List.of(buy, div));
        when(holdingRepository.save(any(HoldingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recomputeForAccountAndSymbol(account, tenant, "AAPL");

        var captor = ArgumentCaptor.forClass(HoldingEntity.class);
        verify(holdingRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void recomputeHoldings_depositWithdrawal_noSymbol_doesNotCreateHolding() {
        service.recomputeForAccountAndSymbol(account, tenant, null);

        verify(holdingRepository, never()).save(any());
        verify(transactionRepository, never()).findByAccountIdAndSymbol(any(), any());
    }

    @Test
    void recomputeForAccountAndSymbol_newHolding_publishesEvent() {
        var txn = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.0000"));

        when(holdingRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(List.of(txn));
        when(holdingRepository.save(any(HoldingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recomputeForAccountAndSymbol(account, tenant, "AAPL");

        var captor = ArgumentCaptor.forClass(NewHoldingCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().symbol()).isEqualTo("AAPL");
    }

    @Test
    void recomputeForAccountAndSymbol_existingHolding_doesNotPublishEvent() {
        var txn = new TransactionEntity(account, tenant, LocalDate.now(), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.0000"));

        var existing = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("5"), new BigDecimal("750.0000"));
        when(holdingRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(Optional.of(existing));
        when(transactionRepository.findByAccountIdAndSymbol(any(), any())).thenReturn(List.of(txn));
        when(holdingRepository.save(any(HoldingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recomputeForAccountAndSymbol(account, tenant, "AAPL");

        verify(eventPublisher, never()).publishEvent(any());
    }
}
