package com.wealthview.persistence.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link TransactionType} onto the existing {@code transactions.type} text column.
 *
 * <p>Deliberately NOT {@code autoApply} — it is wired explicitly with {@code @Convert} on
 * {@link TransactionEntity#getType()} so it can never silently capture some other enum-typed
 * column. Stores {@link TransactionType#value()} (the lowercase snake_case token), which is
 * byte-identical to what the column held when the field was a {@code String}; that is what
 * makes this a no-migration change and keeps the V011 CHECK constraint satisfied.
 */
@Converter(autoApply = false)
public class TransactionTypeConverter implements AttributeConverter<TransactionType, String> {

    @Override
    public String convertToDatabaseColumn(TransactionType attribute) {
        return attribute != null ? attribute.value() : null;
    }

    @Override
    public TransactionType convertToEntityAttribute(String dbData) {
        return dbData != null ? TransactionType.fromValue(dbData) : null;
    }
}
