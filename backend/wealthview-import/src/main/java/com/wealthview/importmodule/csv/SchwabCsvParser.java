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
import java.util.Set;

@Component("schwabCsvParser")
public class SchwabCsvParser extends AbstractBrokerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Date";
    private static final Map<String, String> ACTION_MAP = Map.ofEntries(
            Map.entry("BUY", "buy"),
            Map.entry("SELL", "sell"),
            Map.entry("BUY TO COVER", "buy"),
            Map.entry("SELL SHORT", "sell"),
            Map.entry("CASH DIVIDEND", "dividend"),
            Map.entry("QUALIFIED DIVIDEND", "dividend"),
            Map.entry("NON-QUALIFIED DIVIDEND", "dividend"),
            Map.entry("SPECIAL DIVIDEND", "dividend"),
            Map.entry("PRIOR YEAR CASH DIVIDEND", "dividend"),
            Map.entry("REINVEST DIVIDEND", "buy"),
            Map.entry("QUAL DIV REINVEST", "buy"),
            Map.entry("PRIOR YEAR DIV REINVEST", "buy"),
            Map.entry("LONG TERM CAP GAIN REINVEST", "buy"),
            Map.entry("SHORT TERM CAP GAIN REINVEST", "buy"),
            Map.entry("BANK INTEREST", "dividend"),
            Map.entry("CREDIT INTEREST", "dividend"),
            Map.entry("MARGIN INTEREST", "withdrawal"),
            Map.entry("CASH IN LIEU", "dividend")
    );

    private static final Set<String> SIGN_DEPENDENT_ACTIONS = Set.of(
            "MONEYLINK TRANSFER",
            "WIRE FUNDS",
            "JOURNAL"
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
    protected boolean isHeaderLine(String line) {
        var stripped = line.replace("\"", "").trim();
        return stripped.startsWith(HEADER_MARKER + ",") || stripped.equals(HEADER_MARKER);
    }

    @Override
    protected void extractRow(CSVRecord record, int rowNum,
                              List<ParsedTransaction> transactions, List<CsvRowError> errors) {
        var dateStr = record.get("Date");
        if (dateStr == null || dateStr.isBlank() || isFooterRow(dateStr)) {
            return;
        }

        LocalDate date;
        try {
            date = parseDate(dateStr);
        } catch (DateTimeParseException e) {
            return;
        }

        var amount = parseOptionalAmount(record.get("Amount"));
        var type = mapAction(record.get("Action"), amount);
        if (type == null) {
            errors.add(new CsvRowError(rowNum, "Unknown action: " + record.get("Action")));
            return;
        }

        var quantity = parseOptionalAmount(record.get("Quantity"));
        var absAmount = amount != null ? amount.abs() : null;
        var parsedSymbol = parseOptionalSymbol(record.get("Symbol"));
        transactions.add(new ParsedTransaction(date, type, parsedSymbol, quantity, absAmount));
    }

    private boolean isFooterRow(String dateStr) {
        return dateStr.contains("Total") || dateStr.contains("total");
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

        if (SIGN_DEPENDENT_ACTIONS.contains(upper)) {
            return (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) ? "withdrawal" : "deposit";
        }

        return null;
    }
}
