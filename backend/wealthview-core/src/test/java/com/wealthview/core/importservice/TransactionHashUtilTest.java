package com.wealthview.core.importservice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionHashUtilTest {

    @Test
    void computeHash_sameInputs_returnsSameHash() {
        var hash1 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "AAPL",
                new BigDecimal("10.0000"), new BigDecimal("1500.0000"));
        var hash2 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "AAPL",
                new BigDecimal("10.0000"), new BigDecimal("1500.0000"));

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeHash_differentDate_returnsDifferentHash() {
        var hash1 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        var hash2 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 16), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeHash_differentType_returnsDifferentHash() {
        var hash1 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        var hash2 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "sell", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeHash_differentSymbol_returnsDifferentHash() {
        var hash1 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        var hash2 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "GOOG",
                new BigDecimal("10"), new BigDecimal("1500"));

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeHash_nullSymbol_producesValidHash() {
        var hash = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "deposit", null,
                null, new BigDecimal("5000"));

        assertThat(hash).isNotNull().isNotBlank();
    }

    @Test
    void computeHash_nullQuantity_producesValidHash() {
        var hash = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "dividend", "AAPL",
                null, new BigDecimal("25.50"));

        assertThat(hash).isNotNull().isNotBlank();
    }

    @Test
    void computeHash_nullSymbolVsEmptySymbol_returnsDifferentHash() {
        var hash1 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "deposit", null,
                null, new BigDecimal("5000"));
        var hash2 = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "deposit", "",
                null, new BigDecimal("5000"));

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeHash_returnsHexString() {
        var hash = TransactionHashUtil.computeHash(
                LocalDate.of(2024, 1, 15), "buy", "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));

        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
