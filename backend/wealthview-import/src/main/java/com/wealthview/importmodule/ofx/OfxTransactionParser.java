package com.wealthview.importmodule.ofx;

import com.webcohesion.ofx4j.domain.data.MessageSetType;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import com.webcohesion.ofx4j.domain.data.banking.BankStatementResponse;
import com.webcohesion.ofx4j.domain.data.banking.BankStatementResponseTransaction;
import com.webcohesion.ofx4j.domain.data.banking.BankingResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.common.Transaction;
import com.webcohesion.ofx4j.domain.data.common.TransactionType;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponse;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponseTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseBuyInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseSellInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.IncomeTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.InvestmentBankTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.ReinvestIncomeTransaction;
import com.webcohesion.ofx4j.domain.data.seclist.BaseSecurityInfo;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityList;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityListResponseMessageSet;
import com.webcohesion.ofx4j.io.AggregateUnmarshaller;
import com.wealthview.core.importservice.CsvParser;
import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter from OFX4J's {@link ResponseEnvelope} format to the app's {@link CsvParseResult}.
 *
 * <p>This class implements {@link CsvParser} — a functional interface whose single method accepts
 * an {@link InputStream} and returns a {@link CsvParseResult}. The interface name is a historical
 * artifact: it really represents "any file parser that produces a list of transactions and errors".
 * OFX/QFX files are not CSVs, but the contract is identical, so this Adapter fits without any
 * interface changes.
 *
 * <p>Adapter mapping:
 * <ul>
 *   <li>Adaptee: OFX4J {@link AggregateUnmarshaller} producing a {@link ResponseEnvelope}</li>
 *   <li>Target: {@link CsvParser#parse(InputStream)} returning {@link CsvParseResult}</li>
 *   <li>Investment transactions (buy/sell/income/reinvest) → {@link ParsedTransaction}</li>
 *   <li>Banking transactions (deposits/withdrawals) → {@link ParsedTransaction}</li>
 * </ul>
 *
 * <p>Bean name {@code "ofxParser"} is resolved by {@code ImportService} for OFX/QFX uploads.
 */
@Component("ofxParser")
@SuppressWarnings({"PMD.GodClass", "PMD.CouplingBetweenObjects"})
public class OfxTransactionParser implements CsvParser {

    private static final Logger log = LoggerFactory.getLogger(OfxTransactionParser.class);

    /**
     * Maps OFX4J {@link TransactionType} enum values to the app's internal transaction type strings.
     * Types not present in this map fall back to sign-based inference in
     * {@link #mapBankTransactionType(TransactionType, BigDecimal)}.
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

    /**
     * Adapter entry point. Unmarshals the OFX/QFX stream via OFX4J, then delegates to
     * {@link #extractInvestmentTransactions} and {@link #extractBankTransactions} to convert
     * the domain-specific OFX model into the generic {@link CsvParseResult}.
     */
    @Override
    public CsvParseResult parse(InputStream inputStream) throws IOException {
        var transactions = new ArrayList<ParsedTransaction>();
        var errors = new ArrayList<CsvRowError>();

        ResponseEnvelope envelope;
        try {
            var unmarshaller = new AggregateUnmarshaller<>(ResponseEnvelope.class);
            envelope = unmarshaller.unmarshal(inputStream);
        } catch (IOException | com.webcohesion.ofx4j.io.OFXParseException e) {
            log.warn("Failed to parse OFX file", e);
            errors.add(new CsvRowError(0, "Failed to parse OFX file: " + e.getMessage()));
            return new CsvParseResult(transactions, errors);
        }

        var tickerMap = buildTickerMap(envelope);

        extractInvestmentTransactions(envelope, tickerMap, transactions, errors);
        extractBankTransactions(envelope, transactions, errors);

        return new CsvParseResult(transactions, errors);
    }

    private Map<String, String> buildTickerMap(ResponseEnvelope envelope) {
        var map = new HashMap<String, String>();
        var secListMsgSet = envelope.getMessageSet(MessageSetType.investment_security);
        if (secListMsgSet instanceof SecurityListResponseMessageSet secListResponse) {
            SecurityList secList = secListResponse.getSecurityList();
            if (secList != null && secList.getSecurityInfos() != null) {
                for (BaseSecurityInfo info : secList.getSecurityInfos()) {
                    var secId = info.getSecurityId();
                    var ticker = info.getTickerSymbol();
                    if (secId != null && ticker != null && !ticker.isBlank()) {
                        map.put(secId.getUniqueId(), ticker);
                    }
                }
            }
        }
        return map;
    }

    private void extractInvestmentTransactions(ResponseEnvelope envelope,
                                                Map<String, String> tickerMap,
                                                List<ParsedTransaction> transactions,
                                                List<CsvRowError> errors) {
        var msgSet = envelope.getMessageSet(MessageSetType.investment);
        if (!(msgSet instanceof InvestmentStatementResponseMessageSet invMsgSet)) {
            return;
        }

        for (InvestmentStatementResponseTransaction stmtTxn : invMsgSet.getStatementResponses()) {
            InvestmentStatementResponse stmt = stmtTxn.getMessage();
            if (stmt == null || stmt.getInvestmentTransactionList() == null) {
                continue;
            }

            var txnList = stmt.getInvestmentTransactionList();
            processInvestmentTransactionList(txnList.getInvestmentTransactions(), tickerMap, transactions, errors);
            processBankTransactionsInStatement(txnList.getBankTransactions(), transactions, errors);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void processInvestmentTransactionList(List<BaseInvestmentTransaction> invTxns,
                                                   Map<String, String> tickerMap,
                                                   List<ParsedTransaction> transactions,
                                                   List<CsvRowError> errors) {
        if (invTxns == null) {
            return;
        }
        int rowNum = 0;
        for (BaseInvestmentTransaction invTxn : invTxns) {
            rowNum++;
            try {
                var parsed = mapInvestmentTransaction(invTxn, tickerMap);
                if (parsed != null) {
                    transactions.add(parsed);
                }
            } catch (Exception e) {
                errors.add(new CsvRowError(rowNum, "Error parsing investment transaction: " + e.getMessage()));
            }
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void processBankTransactionsInStatement(List<InvestmentBankTransaction> bankTxns,
                                                     List<ParsedTransaction> transactions,
                                                     List<CsvRowError> errors) {
        if (bankTxns == null) {
            return;
        }
        int rowNum = 0;
        for (InvestmentBankTransaction bankTxn : bankTxns) {
            rowNum++;
            try {
                var parsed = mapBankingTransaction(bankTxn.getTransaction());
                if (parsed != null) {
                    transactions.add(parsed);
                }
            } catch (Exception e) {
                errors.add(new CsvRowError(rowNum, "Error parsing bank transaction in investment statement: " + e.getMessage()));
            }
        }
    }

    private ParsedTransaction mapInvestmentTransaction(BaseInvestmentTransaction invTxn,
                                                        Map<String, String> tickerMap) {
        if (invTxn instanceof BaseBuyInvestmentTransaction buy) {
            var secId = buy.getSecurityId();
            var symbol = resolveSymbol(secId, tickerMap);
            var date = toLocalDate(buy.getTradeDate());
            var units = buy.getUnits();
            var total = buy.getTotal();
            return new ParsedTransaction(
                    date,
                    "buy",
                    symbol,
                    units != null ? BigDecimal.valueOf(Math.abs(units)) : null,
                    total != null ? BigDecimal.valueOf(Math.abs(total)) : null
            );
        }

        if (invTxn instanceof BaseSellInvestmentTransaction sell) {
            var secId = sell.getSecurityId();
            var symbol = resolveSymbol(secId, tickerMap);
            var date = toLocalDate(sell.getTradeDate());
            var units = sell.getUnits();
            var total = sell.getTotal();
            return new ParsedTransaction(
                    date,
                    "sell",
                    symbol,
                    units != null ? BigDecimal.valueOf(Math.abs(units)) : null,
                    total != null ? BigDecimal.valueOf(Math.abs(total)) : null
            );
        }

        if (invTxn instanceof IncomeTransaction income) {
            var secId = income.getSecurityId();
            var symbol = resolveSymbol(secId, tickerMap);
            var date = toLocalDate(income.getTradeDate());
            var total = income.getTotal();
            return new ParsedTransaction(
                    date,
                    "dividend",
                    symbol,
                    null,
                    total != null ? BigDecimal.valueOf(Math.abs(total)) : null
            );
        }

        if (invTxn instanceof ReinvestIncomeTransaction reinvest) {
            var secId = reinvest.getSecurityId();
            var symbol = resolveSymbol(secId, tickerMap);
            var date = toLocalDate(reinvest.getTradeDate());
            var units = reinvest.getUnits();
            var total = reinvest.getTotal();
            return new ParsedTransaction(
                    date,
                    "buy",
                    symbol,
                    units != null ? BigDecimal.valueOf(Math.abs(units)) : null,
                    total != null ? BigDecimal.valueOf(Math.abs(total)) : null
            );
        }

        return null;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void extractBankTransactions(ResponseEnvelope envelope,
                                          List<ParsedTransaction> transactions,
                                          List<CsvRowError> errors) {
        var msgSet = envelope.getMessageSet(MessageSetType.banking);
        if (!(msgSet instanceof BankingResponseMessageSet bankMsgSet)) {
            return;
        }

        for (BankStatementResponseTransaction stmtTxn : bankMsgSet.getStatementResponses()) {
            BankStatementResponse stmt = stmtTxn.getMessage();
            if (stmt == null || stmt.getTransactionList() == null) {
                continue;
            }

            int rowNum = 0;
            for (Transaction txn : stmt.getTransactionList().getTransactions()) {
                rowNum++;
                try {
                    var parsed = mapBankingTransaction(txn);
                    if (parsed != null) {
                        transactions.add(parsed);
                    }
                } catch (Exception e) {
                    errors.add(new CsvRowError(rowNum, "Error parsing bank transaction: " + e.getMessage()));
                }
            }
        }
    }

    private ParsedTransaction mapBankingTransaction(Transaction txn) {
        var date = toLocalDate(txn.getDatePosted());
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

    private String mapBankTransactionType(TransactionType txnType, BigDecimal amount) {
        if (txnType != null) {
            var mapped = BANK_TXN_TYPE_MAP.get(txnType);
            if (mapped != null) {
                return mapped;
            }
        }
        return amount.signum() >= 0 ? "deposit" : "withdrawal";
    }

    private String resolveSymbol(com.webcohesion.ofx4j.domain.data.seclist.SecurityId secId,
                                  Map<String, String> tickerMap) {
        if (secId == null) {
            return null;
        }
        var ticker = tickerMap.get(secId.getUniqueId());
        return ticker != null ? ticker : secId.getUniqueId();
    }

    private LocalDate toLocalDate(Date date) {
        if (date == null) {
            return LocalDate.now();
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
