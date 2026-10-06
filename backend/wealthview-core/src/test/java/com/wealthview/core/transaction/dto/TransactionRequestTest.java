package com.wealthview.core.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.wealthview.persistence.entity.TransactionType;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionRequestTest {

    private static TransactionRequest request(TransactionType type, BigDecimal quantity) {
        return new TransactionRequest(LocalDate.of(2025, 1, 15), type, "AAPL", quantity, new BigDecimal("-100"));
    }

    @Test
    void isQuantityValidForType_buyWithPositiveQuantity_isValid() {
        assertThat(request(TransactionType.BUY, BigDecimal.ONE).isQuantityValidForType()).isTrue();
    }

    @Test
    void isQuantityValidForType_sellWithZeroQuantity_isInvalid() {
        assertThat(request(TransactionType.SELL, BigDecimal.ZERO).isQuantityValidForType()).isFalse();
    }

    @Test
    void isQuantityValidForType_buyWithoutQuantity_isValidSoImportedRowsStayEditable() {
        assertThat(request(TransactionType.BUY, null).isQuantityValidForType()).isTrue();
    }

    @Test
    void isQuantityValidForType_dividendWithZeroQuantity_isValid() {
        assertThat(request(TransactionType.DIVIDEND, BigDecimal.ZERO).isQuantityValidForType()).isTrue();
    }
}
