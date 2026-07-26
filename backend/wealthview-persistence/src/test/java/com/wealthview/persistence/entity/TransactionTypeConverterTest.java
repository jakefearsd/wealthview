package com.wealthview.persistence.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionTypeConverterTest {

    private final TransactionTypeConverter converter = new TransactionTypeConverter();

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void convert_everyConstant_roundtripsThroughItsWireValue(TransactionType type) {
        var column = converter.convertToDatabaseColumn(type);

        assertThat(column).isEqualTo(type.value());
        assertThat(converter.convertToEntityAttribute(column)).isSameAs(type);
    }

    @Test
    void convertToDatabaseColumn_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_null_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
