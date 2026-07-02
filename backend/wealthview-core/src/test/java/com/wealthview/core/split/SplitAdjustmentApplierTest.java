package com.wealthview.core.split;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.persistence.entity.StockSplitAdjustmentEntity;
import com.wealthview.persistence.entity.StockSplitEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.StockSplitAdjustmentRepository;
import com.wealthview.persistence.repository.StockSplitRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SplitAdjustmentApplierTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TXN_ID = UUID.randomUUID();
    private static final LocalDate TXN_DATE = LocalDate.of(2019, 1, 1);

    @Mock
    private StockSplitRepository stockSplitRepository;
    @Mock
    private StockSplitAdjustmentRepository adjustmentRepository;
    @InjectMocks
    private SplitAdjustmentApplier applier;

    private TransactionEntity txn(String symbol, BigDecimal quantity) {
        var t = mock(TransactionEntity.class);
        when(t.getSymbol()).thenReturn(symbol);
        when(t.getQuantity()).thenReturn(quantity);
        when(t.getDate()).thenReturn(TXN_DATE);
        when(t.getId()).thenReturn(TXN_ID);
        when(t.getTenantId()).thenReturn(TENANT_ID);
        return t;
    }

    private StockSplitEntity split(int num, int den, LocalDate date) {
        return new StockSplitEntity("AAPL", date, num, den, "manual");
    }

    @Test
    void adjustNewTransaction_noApplicableSplits_leavesQuantityUntouched() {
        var t = txn("AAPL", new BigDecimal("100"));
        when(stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc("AAPL", TXN_DATE))
                .thenReturn(List.of());

        applier.adjustNewTransaction(t);

        verify(t, never()).setQuantity(any());
        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void adjustNewTransaction_oneSplit_scalesQuantityAndRecordsRow() {
        var t = txn("AAPL", new BigDecimal("100"));
        when(stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc("AAPL", TXN_DATE))
                .thenReturn(List.of(split(4, 1, LocalDate.of(2020, 8, 31))));

        applier.adjustNewTransaction(t);

        var captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(t).setQuantity(captor.capture());
        assertThat(captor.getValue()).isEqualByComparingTo("400.0000");

        var rowCaptor = ArgumentCaptor.forClass(StockSplitAdjustmentEntity.class);
        verify(adjustmentRepository).save(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getOldValue()).isEqualByComparingTo("100");
        assertThat(rowCaptor.getValue().getNewValue()).isEqualByComparingTo("400");
    }

    @Test
    void adjustNewTransaction_multipleSplits_foldsOldestFirstWithOneRowEach() {
        var t = txn("AAPL", new BigDecimal("10"));
        when(stockSplitRepository
                .findBySymbolAndEffectiveDateGreaterThanEqualOrderByEffectiveDateAsc("AAPL", TXN_DATE))
                .thenReturn(List.of(
                        split(7, 1, LocalDate.of(2014, 6, 9)),
                        split(4, 1, LocalDate.of(2020, 8, 31))));

        applier.adjustNewTransaction(t);

        var finalQty = ArgumentCaptor.forClass(BigDecimal.class);
        verify(t).setQuantity(finalQty.capture());
        assertThat(finalQty.getValue()).isEqualByComparingTo("280.0000");

        var rows = ArgumentCaptor.forClass(StockSplitAdjustmentEntity.class);
        verify(adjustmentRepository, org.mockito.Mockito.times(2)).save(rows.capture());
        assertThat(rows.getAllValues().get(0).getNewValue()).isEqualByComparingTo("70");
        assertThat(rows.getAllValues().get(1).getOldValue()).isEqualByComparingTo("70");
        assertThat(rows.getAllValues().get(1).getNewValue()).isEqualByComparingTo("280");
    }

    @Test
    void adjustNewTransaction_nullSymbol_isNoOp() {
        var t = mock(TransactionEntity.class);
        when(t.getSymbol()).thenReturn(null);
        when(t.getQuantity()).thenReturn(new BigDecimal("100"));

        applier.adjustNewTransaction(t);

        verifyNoInteractions(stockSplitRepository, adjustmentRepository);
    }

    @Test
    void adjustNewTransaction_zeroQuantity_isNoOp() {
        var t = mock(TransactionEntity.class);
        when(t.getSymbol()).thenReturn("AAPL");
        when(t.getQuantity()).thenReturn(BigDecimal.ZERO);

        applier.adjustNewTransaction(t);

        verifyNoInteractions(stockSplitRepository, adjustmentRepository);
    }
}
