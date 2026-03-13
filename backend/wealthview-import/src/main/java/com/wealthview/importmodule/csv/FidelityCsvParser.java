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
import java.util.Locale;
import java.util.Map;

@Component("fidelityCsvParser")
public class FidelityCsvParser extends AbstractBrokerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Run Date";
    private static final Map<String, String> ACTION_MAP = Map.of(
            "YOU BOUGHT", "buy",
            "REINVESTMENT", "buy",
            "YOU SOLD", "sell",
            "DIVIDEND RECEIVED", "dividend"
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
        var dateStr = record.get("Run Date");
        var action = record.get("Action");
        var symbol = record.get("Symbol");
        var quantityStr = record.get("Quantity");
        var amountStr = record.get("Amount ($)");

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

        BigDecimal amount = null;
        if (amountStr != null && !amountStr.isBlank()) {
            amount = parseAmount(amountStr);
        }

        var type = mapAction(action, amount);
        if (type == null) {
            errors.add(new CsvRowError(rowNum, "Unknown action: " + action));
            return;
        }

        BigDecimal quantity = null;
        if (quantityStr != null && !quantityStr.isBlank()) {
            quantity = parseAmount(quantityStr);
        }

        if (amount != null) {
            amount = amount.abs();
        }

        var parsedSymbol = (symbol != null && !symbol.isBlank()) ? symbol : null;
        transactions.add(new ParsedTransaction(date, type, parsedSymbol, quantity, amount));
    }

    String mapAction(String action, BigDecimal amount) {
        if (action == null) {
            return null;
        }
        var upper = action.trim().toUpperCase(Locale.US);

        var mapped = ACTION_MAP.get(upper);
        if (mapped != null) {
            return mapped;
        }

        if ("ELECTRONIC FUNDS TRANSFER".equals(upper)) {
            return (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) ? "withdrawal" : "deposit";
        }

        return null;
    }
}
