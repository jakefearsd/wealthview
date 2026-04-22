package com.wealthview.importmodule.ofx;

import com.webcohesion.ofx4j.domain.data.MessageSetType;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import com.webcohesion.ofx4j.domain.data.banking.BankStatementResponse;
import com.webcohesion.ofx4j.domain.data.banking.BankStatementResponseTransaction;
import com.webcohesion.ofx4j.domain.data.banking.BankingResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.common.Transaction;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponse;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponseTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.BaseInvestmentTransaction;
import com.webcohesion.ofx4j.domain.data.investment.transactions.InvestmentBankTransaction;
import com.webcohesion.ofx4j.domain.data.seclist.BaseSecurityInfo;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityList;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityListResponseMessageSet;
import com.webcohesion.ofx4j.io.AggregateUnmarshaller;
import com.wealthview.core.importservice.ImportParser;
import com.wealthview.core.importservice.dto.ImportParseResult;
import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Adapter from OFX4J's {@link ResponseEnvelope} format to the app's {@link ImportParseResult}.
 *
 * <p>Implements {@link ImportParser} — a functional interface whose single method accepts an
 * {@link InputStream} and returns an {@link ImportParseResult}. OFX/QFX is not CSV, but the parse
 * contract is identical, so this Adapter fits without any interface changes.
 *
 * <p>Per-type mapping is delegated to {@link OfxBankTransactionMapper} and
 * {@link OfxInvestmentTransactionMapper}; this class only orchestrates the two and routes the
 * OFX4J statement tree into them.
 *
 * <p>Bean name {@code "ofxParser"} is resolved by {@code ImportService} for OFX/QFX uploads.
 */
@Component("ofxParser")
public class OfxTransactionParser implements ImportParser {

    private static final Logger log = LoggerFactory.getLogger(OfxTransactionParser.class);
    // Match a DOCTYPE declaration anywhere in the document — case-insensitive,
    // tolerant of whitespace. Any match is rejected as a potential XXE vector.
    private static final Pattern DOCTYPE_PATTERN = Pattern.compile("<!\\s*DOCTYPE", Pattern.CASE_INSENSITIVE);

    @Override
    public ImportParseResult parse(InputStream inputStream) throws IOException {
        var transactions = new ArrayList<ParsedTransaction>();
        var errors = new ArrayList<CsvRowError>();

        var bytes = inputStream.readAllBytes();
        if (DOCTYPE_PATTERN.matcher(new String(bytes, StandardCharsets.UTF_8)).find()) {
            log.warn("Rejected OFX upload containing DOCTYPE declaration (XXE defense)");
            errors.add(new CsvRowError(0, "Failed to parse OFX file: DOCTYPE declarations are not permitted"));
            return new ImportParseResult(transactions, errors);
        }

        ResponseEnvelope envelope;
        try {
            var unmarshaller = new AggregateUnmarshaller<>(ResponseEnvelope.class);
            envelope = unmarshaller.unmarshal(new ByteArrayInputStream(bytes));
        } catch (IOException | com.webcohesion.ofx4j.io.OFXParseException e) {
            log.warn("Failed to parse OFX file", e);
            errors.add(new CsvRowError(0, "Failed to parse OFX file: " + e.getMessage()));
            return new ImportParseResult(transactions, errors);
        }

        var tickerMap = buildTickerMap(envelope);

        extractInvestmentTransactions(envelope, tickerMap, transactions, errors);
        extractBankTransactions(envelope, transactions, errors);

        return new ImportParseResult(transactions, errors);
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
                var parsed = OfxInvestmentTransactionMapper.map(invTxn, tickerMap);
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
                var parsed = OfxBankTransactionMapper.map(bankTxn.getTransaction());
                if (parsed != null) {
                    transactions.add(parsed);
                }
            } catch (Exception e) {
                errors.add(new CsvRowError(rowNum, "Error parsing bank transaction in investment statement: " + e.getMessage()));
            }
        }
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
                    var parsed = OfxBankTransactionMapper.map(txn);
                    if (parsed != null) {
                        transactions.add(parsed);
                    }
                } catch (Exception e) {
                    errors.add(new CsvRowError(rowNum, "Error parsing bank transaction: " + e.getMessage()));
                }
            }
        }
    }
}
