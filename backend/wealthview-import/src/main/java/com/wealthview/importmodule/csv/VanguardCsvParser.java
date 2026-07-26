package com.wealthview.importmodule.csv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import com.wealthview.persistence.entity.TransactionType;

@Component("vanguardCsvParser")
public class VanguardCsvParser extends AbstractBrokerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Trade Date";
    private static final Map<String, TransactionType> ACTION_MAP = Map.ofEntries(
            Map.entry("BUY", TransactionType.BUY),
            Map.entry("SELL", TransactionType.SELL),
            Map.entry("DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("REINVESTMENT", TransactionType.BUY),
            Map.entry("CAPITAL GAIN (LT)", TransactionType.DIVIDEND),
            Map.entry("CAPITAL GAIN (ST)", TransactionType.DIVIDEND),
            Map.entry("TRANSFER (INCOMING)", TransactionType.DEPOSIT),
            Map.entry("TRANSFER (OUTGOING)", TransactionType.WITHDRAWAL),
            Map.entry("SWEEP IN", TransactionType.DEPOSIT),
            Map.entry("SWEEP OUT", TransactionType.WITHDRAWAL)
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

        var transactionType = record.get("Transaction Type");
        var type = mapAction(transactionType);
        if (type == null) {
            errors.add(new CsvRowError(rowNum, "Unknown transaction type: " + transactionType));
            return;
        }

        var quantity = parseOptionalAmount(record.get("Shares"));
        var amount = parseOptionalAbsAmount(record.get("Net Amount"));
        var parsedSymbol = parseOptionalSymbol(record.get("Symbol"));
        transactions.add(new ParsedTransaction(date, type, parsedSymbol, quantity, amount));
    }

    private BigDecimal parseOptionalAbsAmount(String value) {
        return (value != null && !value.isBlank()) ? parseAmount(value).abs() : null;
    }

    TransactionType mapAction(String transactionType) {
        if (transactionType == null) {
            return null;
        }
        var upper = transactionType.trim().toUpperCase(Locale.US);
        return ACTION_MAP.get(upper);
    }
}
