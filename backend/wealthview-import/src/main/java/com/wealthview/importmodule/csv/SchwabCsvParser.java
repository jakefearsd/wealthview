package com.wealthview.importmodule.csv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import com.wealthview.persistence.entity.TransactionType;

@Component("schwabCsvParser")
public class SchwabCsvParser extends AbstractBrokerCsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Date";
    private static final Map<String, TransactionType> ACTION_MAP = Map.ofEntries(
            Map.entry("BUY", TransactionType.BUY),
            Map.entry("SELL", TransactionType.SELL),
            Map.entry("BUY TO COVER", TransactionType.BUY),
            Map.entry("SELL SHORT", TransactionType.SELL),
            Map.entry("CASH DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("QUALIFIED DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("NON-QUALIFIED DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("SPECIAL DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("PRIOR YEAR CASH DIVIDEND", TransactionType.DIVIDEND),
            Map.entry("REINVEST DIVIDEND", TransactionType.BUY),
            Map.entry("QUAL DIV REINVEST", TransactionType.BUY),
            Map.entry("PRIOR YEAR DIV REINVEST", TransactionType.BUY),
            Map.entry("LONG TERM CAP GAIN REINVEST", TransactionType.BUY),
            Map.entry("SHORT TERM CAP GAIN REINVEST", TransactionType.BUY),
            Map.entry("BANK INTEREST", TransactionType.DIVIDEND),
            Map.entry("CREDIT INTEREST", TransactionType.DIVIDEND),
            Map.entry("MARGIN INTEREST", TransactionType.WITHDRAWAL),
            Map.entry("CASH IN LIEU", TransactionType.DIVIDEND)
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

        addTransaction(date, type, amount, record, transactions);
    }

    private boolean isFooterRow(String dateStr) {
        return dateStr.contains("Total") || dateStr.contains("total");
    }

    TransactionType mapAction(String action, BigDecimal amount) {
        if (action == null) {
            return null;
        }
        var upper = action.trim().toUpperCase(Locale.US);

        var mapped = ACTION_MAP.get(upper);
        if (mapped != null) {
            return mapped;
        }

        if (SIGN_DEPENDENT_ACTIONS.contains(upper)) {
            return (amount != null && amount.compareTo(BigDecimal.ZERO) < 0)
                    ? TransactionType.WITHDRAWAL : TransactionType.DEPOSIT;
        }

        return null;
    }
}
