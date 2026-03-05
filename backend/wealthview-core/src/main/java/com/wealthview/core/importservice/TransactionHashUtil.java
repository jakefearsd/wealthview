package com.wealthview.core.importservice;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

public final class TransactionHashUtil {

    private TransactionHashUtil() {
    }

    public static String computeHash(LocalDate date, String type, String symbol,
                                     BigDecimal quantity, BigDecimal amount) {
        var input = date.toString() + "|"
                + type + "|"
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
