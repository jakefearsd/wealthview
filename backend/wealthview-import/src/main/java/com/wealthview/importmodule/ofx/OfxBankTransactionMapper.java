package com.wealthview.importmodule.ofx;

import com.webcohesion.ofx4j.domain.data.common.Transaction;
import com.webcohesion.ofx4j.domain.data.common.TransactionType;
import com.wealthview.core.importservice.dto.ParsedTransaction;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Maps OFX4J banking transactions to the app's {@link ParsedTransaction} model.
 * Extracted from OfxTransactionParser to keep that class focused on orchestration.
 */
final class OfxBankTransactionMapper {

    /**
     * OFX {@link TransactionType} → app transaction-type string. Values not present
     * here fall through to sign-based inference in {@link #mapBankTransactionType}.
     */
    private static final Map<TransactionType, String> BANK_TXN_TYPE_MAP;
    static {
        var map = new EnumMap<TransactionType, String>(TransactionType.class);
        map.put(TransactionType.CREDIT, "deposit");
        map.put(TransactionType.DEP, "deposit");
        map.put(TransactionType.DIRECTDEP, "deposit");
        map.put(TransactionType.DEBIT, "withdrawal");
        map.put(TransactionType.CHECK, "withdrawal");
        map.put(TransactionType.PAYMENT, "withdrawal");
        map.put(TransactionType.POS, "withdrawal");
        map.put(TransactionType.ATM, "withdrawal");
        map.put(TransactionType.DIRECTDEBIT, "withdrawal");
        map.put(TransactionType.DIV, "dividend");
        map.put(TransactionType.INT, "dividend");
        BANK_TXN_TYPE_MAP = Map.copyOf(map);
    }

    private OfxBankTransactionMapper() {
    }

    /** Returns a parsed transaction, or null if the OFX record has no usable amount. */
    static ParsedTransaction map(Transaction txn) {
        var date = OfxDateUtils.toLocalDate(txn.getDatePosted());
        var amount = txn.getBigDecimalAmount();
        if (amount == null && txn.getAmount() != null) {
            amount = BigDecimal.valueOf(txn.getAmount());
        }
        if (amount == null) {
            return null;
        }

        var type = mapBankTransactionType(txn.getTransactionType(), amount);
        return new ParsedTransaction(date, type, null, null, amount.abs());
    }

    private static String mapBankTransactionType(TransactionType txnType, BigDecimal amount) {
        if (txnType != null) {
            var mapped = BANK_TXN_TYPE_MAP.get(txnType);
            if (mapped != null) {
                return mapped;
            }
        }
        return amount.signum() >= 0 ? "deposit" : "withdrawal";
    }
}
