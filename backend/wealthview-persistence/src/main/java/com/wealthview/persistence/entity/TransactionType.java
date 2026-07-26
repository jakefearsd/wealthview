package com.wealthview.persistence.entity;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The closed set of transaction types WealthView records, and the single source of truth for it.
 *
 * <p>Before this enum existed the set was declared independently in four places — a
 * {@code Set<String>} in the generic CSV parser, a Bean Validation {@code @Pattern} on the
 * request DTO, the {@code transactions_type_check} database CHECK constraint (V011), and the
 * broker parsers' action maps — and they had drifted: the generic CSV parser rejected
 * {@code opening_balance} that every other declaration accepted.
 *
 * <p>{@link #value()} is the canonical lowercase snake_case token used for BOTH the JSON wire
 * format and the {@code transactions.type} text column, so adopting the enum changes neither.
 * Persistence goes through {@link TransactionTypeConverter}; JSON goes through the
 * {@code @JsonValue} / {@code @JsonCreator} pair below. The wire values must stay in lockstep
 * with the V011 CHECK constraint — adding a constant requires a Flyway migration.
 */
public enum TransactionType {

    /** Purchase of a security: increases quantity and cost basis. */
    BUY("buy"),

    /** Disposal of a security: reduces quantity, cost basis follows at average cost. */
    SELL("sell"),

    /** Cash distribution (dividend, interest, capital-gain distribution). */
    DIVIDEND("dividend"),

    /** Cash into the account. The only type that adds to a cash-account balance. */
    DEPOSIT("deposit"),

    /** Cash out of the account. */
    WITHDRAWAL("withdrawal"),

    /**
     * Synthetic starting position produced by a position-snapshot import (e.g. Fidelity
     * positions CSV), where no purchase history is available. Treated like a {@link #BUY}
     * when holdings are recomputed.
     */
    OPENING_BALANCE("opening_balance");

    private final String value;

    TransactionType(String value) {
        this.value = value;
    }

    /** The canonical lowercase token used on the JSON wire and in the database column. */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Parses a wire/storage token into its constant, tolerating surrounding whitespace and
     * any letter case (broker CSVs arrive uppercase).
     *
     * @param raw the token to parse, e.g. {@code "opening_balance"}
     * @return the matching constant
     * @throws IllegalArgumentException if {@code raw} is null or matches no constant
     */
    @JsonCreator
    public static TransactionType fromValue(String raw) {
        if (raw != null) {
            var normalized = raw.trim().toLowerCase(Locale.US);
            for (var type : values()) {
                if (type.value.equals(normalized)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Unknown transaction type: " + raw);
    }
}
