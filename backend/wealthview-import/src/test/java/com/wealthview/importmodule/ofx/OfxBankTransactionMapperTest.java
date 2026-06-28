package com.wealthview.importmodule.ofx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.junit.jupiter.api.Test;

import com.webcohesion.ofx4j.domain.data.common.Transaction;
import com.webcohesion.ofx4j.domain.data.common.TransactionType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfxBankTransactionMapperTest {

    private static Date dateOf(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void map_creditWithBigDecimalAmount_isDepositWithPostedDateAndAbsoluteAmount() {
        Transaction txn = mock(Transaction.class);
        when(txn.getDatePosted()).thenReturn(dateOf(LocalDate.of(2024, 3, 15)));
        when(txn.getBigDecimalAmount()).thenReturn(new BigDecimal("250.00"));
        when(txn.getTransactionType()).thenReturn(TransactionType.CREDIT);

        var parsed = OfxBankTransactionMapper.map(txn);

        assertThat(parsed.date()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(parsed.type()).isEqualTo("deposit");
        assertThat(parsed.amount()).isEqualByComparingTo("250.00");
    }

    @Test
    void map_noBigDecimalAmountButDoubleAmount_fallsBackToDoubleAndAbsolutizes() {
        Transaction txn = mock(Transaction.class);
        when(txn.getBigDecimalAmount()).thenReturn(null);
        when(txn.getAmount()).thenReturn(-42.50);
        when(txn.getTransactionType()).thenReturn(TransactionType.DEBIT);

        var parsed = OfxBankTransactionMapper.map(txn);

        assertThat(parsed.type()).isEqualTo("withdrawal");
        assertThat(parsed.amount()).isEqualByComparingTo("42.50");
    }

    @Test
    void map_noAmountAtAll_returnsNull() {
        Transaction txn = mock(Transaction.class);
        when(txn.getBigDecimalAmount()).thenReturn(null);
        when(txn.getAmount()).thenReturn(null);

        assertThat(OfxBankTransactionMapper.map(txn)).isNull();
    }

    @Test
    void map_nullTransactionType_positiveAmount_infersDeposit() {
        Transaction txn = mock(Transaction.class);
        when(txn.getBigDecimalAmount()).thenReturn(new BigDecimal("100.00"));
        when(txn.getTransactionType()).thenReturn(null);

        assertThat(OfxBankTransactionMapper.map(txn).type()).isEqualTo("deposit");
    }

    @Test
    void map_nullTransactionType_negativeAmount_infersWithdrawal() {
        Transaction txn = mock(Transaction.class);
        when(txn.getBigDecimalAmount()).thenReturn(new BigDecimal("-100.00"));
        when(txn.getTransactionType()).thenReturn(null);

        assertThat(OfxBankTransactionMapper.map(txn).type()).isEqualTo("withdrawal");
    }

    @Test
    void map_unmappedTransactionType_fallsBackToSignInference() {
        Transaction txn = mock(Transaction.class);
        when(txn.getBigDecimalAmount()).thenReturn(new BigDecimal("-30.00"));
        when(txn.getTransactionType()).thenReturn(TransactionType.OTHER);

        assertThat(OfxBankTransactionMapper.map(txn).type()).isEqualTo("withdrawal");
    }

    @Test
    void map_nullPostedDate_defaultsToToday() {
        Transaction txn = mock(Transaction.class);
        when(txn.getDatePosted()).thenReturn(null);
        when(txn.getBigDecimalAmount()).thenReturn(new BigDecimal("10.00"));
        when(txn.getTransactionType()).thenReturn(TransactionType.DEP);

        assertThat(OfxBankTransactionMapper.map(txn).date()).isEqualTo(LocalDate.now());
    }
}
