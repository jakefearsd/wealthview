package com.wealthview.importmodule.ofx;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import com.wealthview.core.importservice.dto.ParsedTransaction;
import com.webcohesion.ofx4j.domain.data.common.Transaction;
import com.webcohesion.ofx4j.domain.data.common.TransactionType;

import static com.wealthview.persistence.entity.TransactionType.DEPOSIT;
import static com.wealthview.persistence.entity.TransactionType.DIVIDEND;
import static com.wealthview.persistence.entity.TransactionType.WITHDRAWAL;

/**
 * Maps OFX4J banking transactions to the app's {@link ParsedTransaction} model.
 * Extracted from OfxTransactionParser to keep that class focused on orchestration.
 *
 * <p>Note the name clash: the imported {@code TransactionType} is OFX4J's wire enum, while
 * WealthView's own {@code TransactionType} constants arrive by static import (Java has no
 * import aliases). The map below is precisely the translation between the two.
 */
final class OfxBankTransactionMapper {

    /**
     * OFX {@link TransactionType} → WealthView transaction type. Values not present
     * here fall through to sign-based inference in {@link #mapBankTransactionType}.
     */
    private static final Map<TransactionType, com.wealthview.persistence.entity.TransactionType>
            BANK_TXN_TYPE_MAP;

    static {
        var map = new EnumMap<TransactionType, com.wealthview.persistence.entity.TransactionType>(
                TransactionType.class);
        map.put(TransactionType.CREDIT, DEPOSIT);
        map.put(TransactionType.DEP, DEPOSIT);
        map.put(TransactionType.DIRECTDEP, DEPOSIT);
        map.put(TransactionType.DEBIT, WITHDRAWAL);
        map.put(TransactionType.CHECK, WITHDRAWAL);
        map.put(TransactionType.PAYMENT, WITHDRAWAL);
        map.put(TransactionType.POS, WITHDRAWAL);
        map.put(TransactionType.ATM, WITHDRAWAL);
        map.put(TransactionType.DIRECTDEBIT, WITHDRAWAL);
        map.put(TransactionType.DIV, DIVIDEND);
        map.put(TransactionType.INT, DIVIDEND);
        BANK_TXN_TYPE_MAP = Map.copyOf(map);
    }

    private OfxBankTransactionMapper() {
    }

    /** Returns a parsed transaction, or null if the OFX record has no usable amount. */
    static ParsedTransaction map(Transaction txn) {
        var rawDate = txn.getDatePosted();
        var date = OfxDateUtils.toLocalDate(rawDate != null ? rawDate.toInstant() : null);
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

    private static com.wealthview.persistence.entity.TransactionType mapBankTransactionType(
            TransactionType txnType, BigDecimal amount) {
        if (txnType != null) {
            var mapped = BANK_TXN_TYPE_MAP.get(txnType);
            if (mapped != null) {
                return mapped;
            }
        }
        return amount.signum() >= 0 ? DEPOSIT : WITHDRAWAL;
    }
}
