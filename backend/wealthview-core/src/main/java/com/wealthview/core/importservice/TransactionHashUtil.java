package com.wealthview.core.importservice;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

import com.wealthview.persistence.entity.TransactionType;

public final class TransactionHashUtil {

    private TransactionHashUtil() {
    }

    /**
     * Deduplication hash for an imported transaction. The type contributes
     * {@link TransactionType#value()} — the same lowercase token the field held when it was a
     * {@code String} — so hashes computed before and after the enum adoption are identical and
     * previously-imported rows keep being recognised as duplicates. A null type is folded to
     * {@code "NULL"} like the other optional components; no stored hash can have been computed
     * from one, because {@code transactions.type} is NOT NULL.
     */
    public static String computeHash(LocalDate date, TransactionType type, String symbol,
                                     BigDecimal quantity, BigDecimal amount) {
        var input = date.toString() + "|"
                + (type == null ? "NULL" : type.value()) + "|"
                + (symbol == null ? "NULL" : symbol) + "|"
                + (quantity == null ? "NULL" : quantity.toPlainString()) + "|"
                + (amount == null ? "NULL" : amount.toPlainString());

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
