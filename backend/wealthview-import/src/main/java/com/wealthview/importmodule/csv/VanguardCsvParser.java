package com.wealthview.importmodule.csv;

import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Component("vanguardCsvParser")
public class VanguardCsvParser extends AbstractBrokerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Trade Date";
    private static final Map<String, String> ACTION_MAP = Map.ofEntries(
            Map.entry("BUY", "buy"),
            Map.entry("SELL", "sell"),
            Map.entry("DIVIDEND", "dividend"),
            Map.entry("REINVESTMENT", "buy"),
            Map.entry("CAPITAL GAIN (LT)", "dividend"),
            Map.entry("CAPITAL GAIN (ST)", "dividend"),
            Map.entry("TRANSFER (INCOMING)", "deposit"),
            Map.entry("TRANSFER (OUTGOING)", "withdrawal"),
            Map.entry("SWEEP IN", "deposit"),
            Map.entry("SWEEP OUT", "withdrawal")
    );

    @Override
    protected String getHeaderMarker() {
        return HEADER_MARKER;
    }

    @Override
    protected DateTimeFormatter getDateFormat() {
        return DATE_FORMAT;
    }

    @Override
    protected void extractRow(CSVRecord record, int rowNum,
                              List<ParsedTransaction> transactions, List<CsvRowError> errors) {
        var dateStr = record.get("Trade Date");
        var transactionType = record.get("Transaction Type");
        var symbol = record.get("Symbol");
        var sharesStr = record.get("Shares");
        var netAmountStr = record.get("Net Amount");

        if (dateStr == null || dateStr.isBlank()) {
            return;
        }

        LocalDate date;
        try {
            date = parseDate(dateStr);
        } catch (DateTimeParseException e) {
            errors.add(new CsvRowError(rowNum, "Invalid date: " + dateStr));
            return;
        }

        var type = mapAction(transactionType);
        if (type == null) {
            errors.add(new CsvRowError(rowNum, "Unknown transaction type: " + transactionType));
            return;
        }

        BigDecimal quantity = null;
        if (sharesStr != null && !sharesStr.isBlank()) {
            quantity = parseAmount(sharesStr);
        }

        BigDecimal amount = null;
        if (netAmountStr != null && !netAmountStr.isBlank()) {
            amount = parseAmount(netAmountStr).abs();
        }

        var parsedSymbol = (symbol != null && !symbol.isBlank()) ? symbol : null;
        transactions.add(new ParsedTransaction(date, type, parsedSymbol, quantity, amount));
    }

    String mapAction(String transactionType) {
        if (transactionType == null) return null;
        var upper = transactionType.trim().toUpperCase();
        return ACTION_MAP.get(upper);
    }
}
