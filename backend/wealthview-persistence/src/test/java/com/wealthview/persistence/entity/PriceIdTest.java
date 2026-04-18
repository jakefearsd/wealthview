package com.wealthview.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PriceId is the @IdClass composite key for PriceEntity (symbol + date). Its
 * equals() and hashCode() are load-bearing: Hibernate uses them to build primary-key
 * lookups and identity-map lookups.
 */
class PriceIdTest {

    @Test
    void noArgConstructor_createsEmptyInstance() {
        var id = new PriceId();

        assertThat(id).isNotNull();
    }

    @Test
    void twoArgConstructor_createsInstanceWithValues() {
        var id = new PriceId("AAPL", LocalDate.of(2026, 4, 10));

        assertThat(id).isNotNull();
    }

    @Test
    void equals_sameSymbolAndDate_areEqual() {
        var a = new PriceId("AAPL", LocalDate.of(2026, 4, 10));
        var b = new PriceId("AAPL", LocalDate.of(2026, 4, 10));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_differentSymbol_areNotEqual() {
        var a = new PriceId("AAPL", LocalDate.of(2026, 4, 10));
        var b = new PriceId("MSFT", LocalDate.of(2026, 4, 10));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_differentDate_areNotEqual() {
        var a = new PriceId("AAPL", LocalDate.of(2026, 4, 10));
        var b = new PriceId("AAPL", LocalDate.of(2026, 4, 11));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_sameInstance_isEqualToItself() {
        var id = new PriceId("AAPL", LocalDate.of(2026, 4, 10));

        assertThat(id).isEqualTo(id);
    }

    @Test
    void equals_null_isNotEqual() {
        var id = new PriceId("AAPL", LocalDate.of(2026, 4, 10));

        assertThat(id).isNotEqualTo(null);
    }

    @Test
    void equals_differentClass_isNotEqual() {
        var id = new PriceId("AAPL", LocalDate.of(2026, 4, 10));

        assertThat(id).isNotEqualTo("not a price id");
    }
}
