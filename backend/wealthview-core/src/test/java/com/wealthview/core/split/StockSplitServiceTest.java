package com.wealthview.core.split;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.auth.CrossTenant;
import com.wealthview.core.holding.HoldingsComputationService;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.StockSplitAdjustmentEntity;
import com.wealthview.persistence.entity.StockSplitEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.entity.TransactionType;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.StockSplitAdjustmentRepository;
import com.wealthview.persistence.repository.StockSplitRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static com.wealthview.persistence.entity.TransactionType.BUY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockSplitServiceTest {

    @Mock
    private StockSplitRepository stockSplitRepository;

    @Mock
    private StockSplitAdjustmentRepository adjustmentRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PriceRepository priceRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private HoldingsComputationService holdingsComputationService;

    private MeterRegistry meterRegistry;

    private StockSplitService service;

    private TenantEntity tenant;
    private UUID tenantId;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new StockSplitService(stockSplitRepository, adjustmentRepository, transactionRepository,
                priceRepository, holdingRepository, holdingsComputationService, meterRegistry, true);
        tenant = new TenantEntity("Test");
        tenantId = UUID.randomUUID();
        setId(tenant, tenantId);
        account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
    }

    private static void setId(Object entity, UUID id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void applySplit_singleTransaction_quantityScaledAmountUnchanged() {
        var txnId = UUID.randomUUID();
        var txn = txn(txnId, LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("100"), new BigDecimal("8000.0000"));

        when(stockSplitRepository.findBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of(tenantId));
        when(transactionRepository.findBySymbolAndDateOnOrBefore("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(List.of(txn));
        when(priceRepository.findBySymbolAndDateBefore("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(List.of());

        service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");

        assertThat(txn.getQuantity()).isEqualByComparingTo("400");
        assertThat(txn.getAmount()).isEqualByComparingTo("8000.0000");
    }

    @Test
    void applySplit_recordsAdjustmentRowsForTransactions() {
        var txnId = UUID.randomUUID();
        var txn = txn(txnId, LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("100"), new BigDecimal("8000.0000"));

        when(stockSplitRepository.findBySymbolAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of(tenantId));
        when(transactionRepository.findBySymbolAndDateOnOrBefore(any(), any())).thenReturn(List.of(txn));
        when(priceRepository.findBySymbolAndDateBefore(any(), any())).thenReturn(List.of());

        service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");

        var captor = ArgumentCaptor.forClass(StockSplitAdjustmentEntity.class);
        verify(adjustmentRepository, times(1)).save(captor.capture());
        var adj = captor.getValue();
        assertThat(adj.getTargetTable()).isEqualTo("transactions");
        assertThat(adj.getFieldName()).isEqualTo("quantity");
        assertThat(adj.getOldValue()).isEqualByComparingTo("100");
        assertThat(adj.getNewValue()).isEqualByComparingTo("400");
        assertThat(adj.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void applySplit_idempotent_secondCallSkipped() {
        var existing = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");
        when(stockSplitRepository.findBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(Optional.of(existing));

        var result = service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");

        assertThat(result).isSameAs(existing);
        verify(stockSplitRepository, never()).save(any());
        verify(transactionRepository, never()).findBySymbolAndDateOnOrBefore(any(), any());
    }

    @Test
    void applySplit_reverseSplitOddRatio_adjustsQuantityExactly() {
        // 1:3 reverse split of 300 shares lands on exactly 100.0000 under both the
        // old pre-divided-ratio math and the new SplitMath (300 is a multiple of 3,
        // so the two roundings happen to agree) — pinned here as a regression guard.
        var evenTxn = txn(UUID.randomUUID(), LocalDate.of(2020, 1, 1), BUY, "ABC",
                new BigDecimal("300"), new BigDecimal("3000"));
        // 5003 shares is where the old scale-8 pre-divided ratio (1/3 rounded to
        // 0.33333333) actually drifts: 5003 * 0.33333333 rounds to 1667.6666,
        // one cent short of the exact 1667.6667 that SplitMath.adjustShares produces.
        var oddTxn = txn(UUID.randomUUID(), LocalDate.of(2020, 1, 2), BUY, "ABC",
                new BigDecimal("5003"), new BigDecimal("50030"));

        when(stockSplitRepository.findBySymbolAndEffectiveDate("ABC", LocalDate.of(2020, 8, 31)))
                .thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("ABC")).thenReturn(List.of(tenantId));
        when(transactionRepository.findBySymbolAndDateOnOrBefore("ABC", LocalDate.of(2020, 8, 31)))
                .thenReturn(List.of(evenTxn, oddTxn));
        when(priceRepository.findBySymbolAndDateBefore("ABC", LocalDate.of(2020, 8, 31)))
                .thenReturn(List.of());

        service.applySplit("ABC", LocalDate.of(2020, 8, 31), 1, 3, "manual");

        assertThat(evenTxn.getQuantity()).isEqualByComparingTo("100.0000");
        assertThat(oddTxn.getQuantity()).isEqualByComparingTo("1667.6667");
    }

    @Test
    void applySplit_compoundsCorrectlyOverTwoSplits() {
        // 2:1 forward then 1:2 reverse should restore original quantity.
        var txn = txn(UUID.randomUUID(), LocalDate.of(2020, 1, 1), BUY, "FOO",
                new BigDecimal("10"), new BigDecimal("1000"));

        when(stockSplitRepository.findBySymbolAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("FOO")).thenReturn(List.of(tenantId));
        when(transactionRepository.findBySymbolAndDateOnOrBefore(any(), any())).thenReturn(List.of(txn));
        when(priceRepository.findBySymbolAndDateBefore(any(), any())).thenReturn(List.of());

        service.applySplit("FOO", LocalDate.of(2020, 6, 1), 2, 1, "manual");
        assertThat(txn.getQuantity()).isEqualByComparingTo("20");

        service.applySplit("FOO", LocalDate.of(2021, 6, 1), 1, 2, "manual");
        assertThat(txn.getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void applySplit_skipsManualOverrideHolding_logsWarning() {
        var txn = txn(UUID.randomUUID(), LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("10"), new BigDecimal("1000"));
        var manualHolding = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1000"));
        manualHolding.setManualOverride(true);

        when(stockSplitRepository.findBySymbolAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of(tenantId));
        when(transactionRepository.findBySymbolAndDateOnOrBefore(any(), any())).thenReturn(List.of(txn));
        when(priceRepository.findBySymbolAndDateBefore(any(), any())).thenReturn(List.of());
        when(holdingRepository.findByTenant_Id(tenantId)).thenReturn(List.of(manualHolding));

        service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");

        // Manual override holding is NOT modified directly; HoldingsComputationService
        // is still called but it self-skips manual-override holdings.
        assertThat(manualHolding.getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void applySplit_adjustsHistoricalPrices_whenEnabled() {
        var price = new PriceEntity("AAPL", LocalDate.of(2020, 1, 1), new BigDecimal("400.0000"), "finnhub");

        when(stockSplitRepository.findBySymbolAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of(tenantId));
        when(transactionRepository.findBySymbolAndDateOnOrBefore(any(), any())).thenReturn(List.of());
        when(priceRepository.findBySymbolAndDateBefore("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(List.of(price));

        service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");

        assertThat(price.getClosePrice()).isEqualByComparingTo("100.0000");
    }

    @Test
    void applySplit_skipsHistoricalPrices_whenDisabled() {
        var disabledService = new StockSplitService(stockSplitRepository, adjustmentRepository, transactionRepository,
                priceRepository, holdingRepository, holdingsComputationService, meterRegistry, false);
        var price = new PriceEntity("AAPL", LocalDate.of(2020, 1, 1), new BigDecimal("400.0000"), "finnhub");

        when(stockSplitRepository.findBySymbolAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of(tenantId));
        when(transactionRepository.findBySymbolAndDateOnOrBefore(any(), any())).thenReturn(List.of());

        disabledService.applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");

        assertThat(price.getClosePrice()).isEqualByComparingTo("400.0000");
        verify(priceRepository, never()).findBySymbolAndDateBefore(any(), any());
    }

    @Test
    void applySplit_incrementsAppliedCounter() {
        when(stockSplitRepository.findBySymbolAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of());
        when(transactionRepository.findBySymbolAndDateOnOrBefore(any(), any())).thenReturn(List.of());

        service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");

        var counter = meterRegistry.find("wealthview.splits.applied").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void unapplySplit_restoresOriginalTransactionValues() {
        var txnId = UUID.randomUUID();
        var split = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");
        var splitId = UUID.randomUUID();
        var txn = txn(txnId, LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("400"), new BigDecimal("8000.0000"));
        var adj = new StockSplitAdjustmentEntity(split, tenantId, "transactions", txnId,
                "quantity", new BigDecimal("100"), new BigDecimal("400"));

        when(stockSplitRepository.findById(splitId)).thenReturn(Optional.of(split));
        when(adjustmentRepository.findBySplit_Id(splitId)).thenReturn(List.of(adj));
        when(transactionRepository.findAllById(List.of(txnId))).thenReturn(List.of(txn));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of(tenantId));

        service.unapplySplit(splitId);

        assertThat(txn.getQuantity()).isEqualByComparingTo("100");
        verify(stockSplitRepository).delete(split);
    }

    @Test
    void unapplySplit_thenReapply_endsAtSameState() {
        var txnId = UUID.randomUUID();
        var txn = txn(txnId, LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("100"), new BigDecimal("8000.0000"));

        when(stockSplitRepository.findBySymbolAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of(tenantId));
        when(transactionRepository.findBySymbolAndDateOnOrBefore(any(), any())).thenReturn(List.of(txn));
        when(priceRepository.findBySymbolAndDateBefore(any(), any())).thenReturn(List.of());

        var applied = service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");
        assertThat(txn.getQuantity()).isEqualByComparingTo("400");

        var adjCaptor = ArgumentCaptor.forClass(StockSplitAdjustmentEntity.class);
        verify(adjustmentRepository).save(adjCaptor.capture());
        var capturedAdj = adjCaptor.getValue();
        when(stockSplitRepository.findById(applied.getId())).thenReturn(Optional.of(applied));
        when(adjustmentRepository.findBySplit_Id(applied.getId())).thenReturn(List.of(capturedAdj));
        when(transactionRepository.findAllById(List.of(txnId))).thenReturn(List.of(txn));

        service.unapplySplit(applied.getId());

        assertThat(txn.getQuantity()).isEqualByComparingTo("100");
    }

    @Test
    void listForTenant_filtersBySymbolsTenantHasTransactedIn() {
        var t = new TransactionEntity(account, tenant, LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(transactionRepository.findByTenant_Id(tenantId)).thenReturn(List.of(t));
        var aaplSplit = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub");
        when(stockSplitRepository.findBySymbolsInAndDateRange(
                org.mockito.ArgumentMatchers.argThat(l -> l.contains("AAPL")),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(aaplSplit));

        var result = service.listForTenant(tenantId, null, null, null);

        assertThat(result).containsExactly(aaplSplit);
    }

    @Test
    void listForTenant_emptyWhenTenantHasNoTransactions() {
        when(transactionRepository.findByTenant_Id(tenantId)).thenReturn(List.of());

        var result = service.listForTenant(tenantId, null, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void listForTenant_withSymbolFilter_returnsOnlyMatchingSymbolCaseInsensitively() {
        // The symbol filter predicate must keep only splits whose symbol equals
        // the requested one (ignoring case) and drop the rest.
        var t1 = new TransactionEntity(account, tenant, LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        var t2 = new TransactionEntity(account, tenant, LocalDate.of(2020, 1, 1), BUY, "MSFT",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(transactionRepository.findByTenant_Id(tenantId)).thenReturn(List.of(t1, t2));
        var aaplSplit = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub");
        var msftSplit = new StockSplitEntity("MSFT", LocalDate.of(2021, 2, 1), 2, 1, "finnhub");
        when(stockSplitRepository.findBySymbolsInAndDateRange(any(), any(), any()))
                .thenReturn(List.of(aaplSplit, msftSplit));

        var result = service.listForTenant(tenantId, "aapl", null, null);

        assertThat(result).containsExactly(aaplSplit);
    }

    @Test
    void listForTenant_blankSymbolFilter_returnsAllSplits() {
        var t = new TransactionEntity(account, tenant, LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        when(transactionRepository.findByTenant_Id(tenantId)).thenReturn(List.of(t));
        var aaplSplit = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub");
        var msftSplit = new StockSplitEntity("MSFT", LocalDate.of(2021, 2, 1), 2, 1, "finnhub");
        when(stockSplitRepository.findBySymbolsInAndDateRange(any(), any(), any()))
                .thenReturn(List.of(aaplSplit, msftSplit));

        var result = service.listForTenant(tenantId, "   ", null, null);

        assertThat(result).containsExactly(aaplSplit, msftSplit);
    }

    @Test
    void applySplit_zeroNumerator_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 0, 1, "manual"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void applySplit_negativeDenominator_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 1, -1, "manual"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void applySplit_zeroDenominator_throwsIllegalArgument() {
        // Boundary: denominator == 0 must be rejected — pins the <=0 guard
        // against a <0 boundary mutant.
        assertThatThrownBy(() -> service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 1, 0, "manual"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void applySplit_ratioOfOneToOne_isAccepted() {
        // 1:1 is the boundary: numerator and denominator are both exactly 1,
        // which must pass the >0 check (a <=0 / <0 mutant boundary).
        when(stockSplitRepository.findBySymbolAndEffectiveDate(any(), any())).thenReturn(Optional.empty());
        when(stockSplitRepository.save(any(StockSplitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of());
        when(transactionRepository.findBySymbolAndDateOnOrBefore(any(), any())).thenReturn(List.of());

        var result = service.applySplit("AAPL", LocalDate.of(2020, 8, 31), 1, 1, "manual");

        assertThat(result).isNotNull();
    }

    @Test
    void unapplySplit_transactionRowMissing_doesNotCountAsRestored() {
        // restoreTransaction must return false (not crash, not count) when the
        // referenced transaction row no longer exists.
        var split = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");
        var splitId = UUID.randomUUID();
        var missingTxnId = UUID.randomUUID();
        var adj = new StockSplitAdjustmentEntity(split, tenantId, "transactions", missingTxnId,
                "quantity", new BigDecimal("100"), new BigDecimal("400"));
        when(stockSplitRepository.findById(splitId)).thenReturn(Optional.of(split));
        when(adjustmentRepository.findBySplit_Id(splitId)).thenReturn(List.of(adj));
        when(transactionRepository.findAllById(List.of(missingTxnId))).thenReturn(List.of());
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of());

        service.unapplySplit(splitId);

        verify(stockSplitRepository).delete(split);
    }

    @Test
    void unapplySplit_restoresPriceRows() {
        // restorePrice must locate the price row by deterministic UUID and
        // restore its old close price.
        var split = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");
        var splitId = UUID.randomUUID();
        var priceDate = LocalDate.of(2020, 1, 1);
        var price = new PriceEntity("AAPL", priceDate, new BigDecimal("100.0000"), "finnhub");
        var priceRowId = UUID.nameUUIDFromBytes(
                ("price:AAPL:" + priceDate).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var adj = new StockSplitAdjustmentEntity(split, tenantId, "prices", priceRowId,
                "close_price", new BigDecimal("400.00000000"), new BigDecimal("100.00000000"));
        when(stockSplitRepository.findById(splitId)).thenReturn(Optional.of(split));
        when(adjustmentRepository.findBySplit_Id(splitId)).thenReturn(List.of(adj));
        when(priceRepository.findBySymbolAndDateBefore("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(List.of(price));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of());

        service.unapplySplit(splitId);

        assertThat(price.getClosePrice()).isEqualByComparingTo("400.0000");
    }

    @Test
    void unapplySplit_manyPriceAdjustments_loadsPriceHistoryOnce() {
        // Restoring must not reload the symbol's full price history per adjustment
        // row — that is O(n^2) in price count and stalls unapply for real datasets.
        var split = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");
        var splitId = UUID.randomUUID();
        var price1 = new PriceEntity("AAPL", LocalDate.of(2020, 1, 1), new BigDecimal("100.0000"), "finnhub");
        var price2 = new PriceEntity("AAPL", LocalDate.of(2020, 1, 2), new BigDecimal("50.0000"), "finnhub");
        var adj1 = new StockSplitAdjustmentEntity(split, tenantId, "prices", priceUuid("AAPL", price1.getDate()),
                "close_price", new BigDecimal("400.00000000"), new BigDecimal("100.00000000"));
        var adj2 = new StockSplitAdjustmentEntity(split, tenantId, "prices", priceUuid("AAPL", price2.getDate()),
                "close_price", new BigDecimal("200.00000000"), new BigDecimal("50.00000000"));
        when(stockSplitRepository.findById(splitId)).thenReturn(Optional.of(split));
        when(adjustmentRepository.findBySplit_Id(splitId)).thenReturn(List.of(adj1, adj2));
        when(priceRepository.findBySymbolAndDateBefore("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(List.of(price1, price2));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of());

        service.unapplySplit(splitId);

        assertThat(price1.getClosePrice()).isEqualByComparingTo("400.0000");
        assertThat(price2.getClosePrice()).isEqualByComparingTo("200.0000");
        verify(priceRepository, times(1)).findBySymbolAndDateBefore("AAPL", LocalDate.of(2020, 8, 31));
    }

    @Test
    void unapplySplit_manyTransactionAdjustments_loadsTransactionsInOneBatch() {
        // Restoring must fetch the affected transactions with a single batched
        // query instead of one SELECT per adjustment row.
        var split = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");
        var splitId = UUID.randomUUID();
        var txn1 = txn(UUID.randomUUID(), LocalDate.of(2020, 1, 1), BUY, "AAPL",
                new BigDecimal("400"), new BigDecimal("8000.0000"));
        var txn2 = txn(UUID.randomUUID(), LocalDate.of(2020, 2, 1), BUY, "AAPL",
                new BigDecimal("40"), new BigDecimal("900.0000"));
        var adj1 = new StockSplitAdjustmentEntity(split, tenantId, "transactions", txn1.getId(),
                "quantity", new BigDecimal("100"), new BigDecimal("400"));
        var adj2 = new StockSplitAdjustmentEntity(split, tenantId, "transactions", txn2.getId(),
                "quantity", new BigDecimal("10"), new BigDecimal("40"));
        when(stockSplitRepository.findById(splitId)).thenReturn(Optional.of(split));
        when(adjustmentRepository.findBySplit_Id(splitId)).thenReturn(List.of(adj1, adj2));
        when(transactionRepository.findAllById(any())).thenReturn(List.of(txn1, txn2));
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of());

        service.unapplySplit(splitId);

        assertThat(txn1.getQuantity()).isEqualByComparingTo("100");
        assertThat(txn2.getQuantity()).isEqualByComparingTo("10");
        verify(transactionRepository, never()).findById(any());
    }

    private static UUID priceUuid(String symbol, LocalDate date) {
        return UUID.nameUUIDFromBytes(
                ("price:" + symbol + ":" + date).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private TransactionEntity txn(UUID id, LocalDate date, TransactionType type, String symbol,
                                  BigDecimal qty, BigDecimal amount) {
        var t = new TransactionEntity(account, tenant, date, type, symbol, qty, amount);
        // tests need a stable id; entity uses field-level @GeneratedValue so we
        // assign via reflection
        try {
            var f = TransactionEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    @Test
    void applySplit_isCrossTenant_soGlobalSplitApplicationIsNeverTenantFiltered() throws NoSuchMethodException {
        // Splits are global market events: applySplit reads transactions across ALL
        // tenants. If the calling thread carries a tenant-scoped Authentication, the
        // TenantFilterAspect would otherwise silently filter those reads to one
        // (possibly stale) tenant — recording the split globally while adjusting
        // nobody (hosted CI runs 29195289770..29203911100). @CrossTenant pins the
        // filter OFF for the whole method.
        var method = StockSplitService.class.getMethod("applySplit",
                String.class, LocalDate.class, int.class, int.class, String.class);

        assertThat(method.getAnnotation(CrossTenant.class))
                .as("applySplit must be @CrossTenant — it operates across all tenants")
                .isNotNull();
    }

    @Test
    void unapplySplit_isCrossTenant_soGlobalSplitReversalIsNeverTenantFiltered() throws NoSuchMethodException {
        var method = StockSplitService.class.getMethod("unapplySplit", UUID.class);

        assertThat(method.getAnnotation(CrossTenant.class))
                .as("unapplySplit must be @CrossTenant — it operates across all tenants")
                .isNotNull();
    }
}
