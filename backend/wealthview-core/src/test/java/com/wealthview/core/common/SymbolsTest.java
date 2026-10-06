package com.wealthview.core.common;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

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

    @Test
    void lookUpExactThenNormalized_exactMatch_doesNotTryNormalisedForm() {
        var queried = new ArrayList<String>();

        var result = Symbols.lookUpExactThenNormalized(" vti", symbol -> {
            queried.add(symbol);
            return Optional.of(symbol);
        }, Optional::isPresent);

        assertThat(result).contains(" vti");
        assertThat(queried).containsExactly(" vti");
    }

    @Test
    void lookUpExactThenNormalized_noExactMatch_triesNormalisedForm() {
        var stored = Map.of("VTI", "row");

        var result = Symbols.lookUpExactThenNormalized(" vti",
                symbol -> Optional.ofNullable(stored.get(symbol)), Optional::isPresent);

        assertThat(result).contains("row");
    }

    @Test
    void lookUpExactThenNormalized_alreadyNormalised_queriesOnce() {
        var queried = new ArrayList<String>();

        var result = Symbols.lookUpExactThenNormalized("VTI", symbol -> {
            queried.add(symbol);
            return Optional.<String>empty();
        }, Optional::isPresent);

        assertThat(result).isEmpty();
        assertThat(queried).containsExactly("VTI");
    }

    @Test
    void lookUpExactThenNormalized_blankSymbol_returnsExactResult() {
        var result = Symbols.lookUpExactThenNormalized("  ", symbol -> Optional.<String>empty(),
                Optional::isPresent);

        assertThat(result).isEmpty();
    }
}
