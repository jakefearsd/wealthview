package com.wealthview.persistence.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionTypeTest {

    @Test
    void value_everyConstant_isLowercaseSnakeCase() {
        assertThat(TransactionType.values())
                .extracting(TransactionType::value)
                .containsExactly("buy", "sell", "dividend", "deposit", "withdrawal", "opening_balance");
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void fromValue_ownWireValue_roundtripsToSameConstant(TransactionType type) {
        assertThat(TransactionType.fromValue(type.value())).isSameAs(type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENING_BALANCE", "Opening_Balance", "  opening_balance  "})
    void fromValue_mixedCaseOrPadded_parsesToConstant(String raw) {
        assertThat(TransactionType.fromValue(raw)).isSameAs(TransactionType.OPENING_BALANCE);
    }

    @Test
    void fromValue_unknownToken_throwsIllegalArgument() {
        assertThatThrownBy(() -> TransactionType.fromValue("teleport"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown transaction type: teleport");
    }

    @Test
    void fromValue_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> TransactionType.fromValue(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void value_isNotTheConstantName_forMultiWordConstant() {
        // name() is UPPER_SNAKE; the wire/column token is lowercase. Guards against a caller
        // reaching for name()/valueOf() and writing "OPENING_BALANCE" into the text column.
        assertThat(TransactionType.OPENING_BALANCE.value())
                .isNotEqualTo(TransactionType.OPENING_BALANCE.name())
                .isEqualTo("opening_balance");
    }
}
