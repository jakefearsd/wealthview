package com.wealthview.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolsTest {

    @ParameterizedTest
    @CsvSource({"AAPL, AAPL", "aapl, AAPL", "' msft ', MSFT", "brk.b, BRK.B"})
    void normalize_symbol_trimsAndUppercases(String raw, String expected) {
        assertThat(Symbols.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void normalize_null_returnsNull() {
        assertThat(Symbols.normalize(null)).isNull();
    }

    @Test
    void normalize_blank_returnsNull() {
        assertThat(Symbols.normalize("   ")).isNull();
    }
}
