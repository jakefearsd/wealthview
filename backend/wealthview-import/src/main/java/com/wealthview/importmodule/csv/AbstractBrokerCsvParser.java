package com.wealthview.importmodule.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.wealthview.core.importservice.ImportParser;
import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ImportParseResult;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import com.wealthview.persistence.entity.TransactionType;

/**
 * Shared base class for broker-specific CSV transaction parsers.
 * Handles the common parse loop: preamble skipping, CSV parsing, row iteration with error collection.
 *
 * <p>{@link #extractRow} is itself a template method covering the shape shared by Fidelity, Schwab,
 * and Vanguard: read the date column, apply the bad-date policy, read the amount and action columns,
 * map the action to a {@link TransactionType} via {@link #getActionMap()} /
 * {@link #getSignDependentActions()}, then delegate to {@link #addTransaction}. Subclasses following
 * that shape only need to declare column names/maps through the hooks below; a broker whose row shape
 * genuinely diverges may still override {@link #extractRow} directly (see {@code FidelityPositionsCsvParser},
 * which implements {@code ImportParser} directly rather than extending this class).
 */
public abstract class AbstractBrokerCsvParser implements ImportParser {

    @Override
    public ImportParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ImportParseResult parse(Reader reader) throws IOException {
        var buffered = new BufferedReader(reader);
        var csvContent = skipPreamble(buffered);

        var transactions = new ArrayList<ParsedTransaction>();
        var errors = new ArrayList<CsvRowError>();

        if (csvContent.isBlank()) {
            return new ImportParseResult(transactions, errors);
        }

        var format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build();

        try (var parser = new CSVParser(new StringReader(csvContent), format)) {
            int rowNum = 1;
            for (var record : parser) {
                rowNum++;
                try {
                    extractRow(record, rowNum, transactions, errors);
                } catch (Exception e) {
                    errors.add(new CsvRowError(rowNum, "Error parsing row: " + e.getMessage()));
                }
            }
        }

        return new ImportParseResult(transactions, errors);
    }

    /**
     * Extract a single CSV row into a ParsedTransaction and add it to the transactions list,
     * or add a CsvRowError to the errors list if the row is invalid.
     * Rows that should be silently skipped (blank date, footer, unparseable date) may simply return
     * without adding to either list.
     *
     * <p>This default implementation is the shared row prologue: date guard (with the bad-date policy
     * from {@link #handleInvalidDate}), amount + action lookup, action mapping, then
     * {@link #addTransaction}. Override only when a broker's row shape doesn't fit this shape.
     */
    protected void extractRow(CSVRecord record, int rowNum,
                              List<ParsedTransaction> transactions, List<CsvRowError> errors) {
        var dateStr = record.get(getDateColumn());
        if (dateStr == null || dateStr.isBlank() || isSkippableRow(dateStr)) {
            return;
        }

        LocalDate date;
        try {
            date = parseDate(dateStr);
        } catch (DateTimeParseException e) {
            handleInvalidDate(rowNum, dateStr, errors);
            return;
        }

        var amount = parseOptionalAmount(record.get(getAmountColumn()));
        var actionColumn = getActionColumn();
        var actionRaw = record.get(actionColumn);
        var type = mapAction(actionRaw, amount);
        if (type == null) {
            errors.add(new CsvRowError(rowNum, "Unknown " + actionColumn.toLowerCase(Locale.US) + ": " + actionRaw));
            return;
        }

        addTransaction(date, type, amount, record, transactions);
    }

    /**
     * Returns the header marker string used to detect where the CSV data begins.
     */
    protected abstract String getHeaderMarker();

    /**
     * Returns the DateTimeFormatter for parsing date strings in this broker's format.
     */
    protected abstract DateTimeFormatter getDateFormat();

    /**
     * Column holding the transaction date, read by the {@link #extractRow} template. Defaults to
     * {@link #getHeaderMarker()}: for every current broker export, the header marker used to find the
     * header row IS the date column's own header text. Override if that ever stops holding.
     */
    protected String getDateColumn() {
        return getHeaderMarker();
    }

    /**
     * Column holding the raw action/transaction-type string looked up in {@link #getActionMap()}.
     * Also used (lower-cased) as the noun in the "Unknown ..." error message. Default "Action".
     */
    protected String getActionColumn() {
        return "Action";
    }

    /**
     * Column holding the signed transaction amount. Default "Amount"; override per broker.
     */
    protected String getAmountColumn() {
        return "Amount";
    }

    /**
     * Column holding the share quantity, read by {@link #addTransaction}. Default "Quantity";
     * Vanguard's export calls this column "Shares".
     */
    protected String getQuantityColumn() {
        return "Quantity";
    }

    /**
     * Maps this broker's uppercased action/transaction-type strings to {@link TransactionType}.
     * Default is empty (every action is "unknown" until a subclass supplies its map).
     */
    protected Map<String, TransactionType> getActionMap() {
        return Map.of();
    }

    /**
     * Uppercased action strings whose {@link TransactionType} depends on the sign of the row's amount
     * (negative amount -&gt; WITHDRAWAL, non-negative -&gt; DEPOSIT) rather than a fixed mapping.
     * Checked only after {@link #getActionMap()} misses. Default is empty.
     */
    protected Set<String> getSignDependentActions() {
        return Set.of();
    }

    /**
     * Bad-date policy hook: called when the date column is non-blank but fails to parse.
     * Default emits a {@link CsvRowError}, matching Fidelity/Vanguard. Schwab overrides this to
     * silently skip the row instead — an intentional, pre-existing asymmetry (Schwab exports are
     * more tolerant of malformed trailing rows), not an oversight.
     */
    protected void handleInvalidDate(int rowNum, String rawDate, List<CsvRowError> errors) {
        errors.add(new CsvRowError(rowNum, "Invalid date: " + rawDate));
    }

    /**
     * Additional per-broker reason (beyond a blank date) to silently skip a row, evaluated against the
     * raw date-column value. Default false. Schwab overrides this to skip its "Total" footer row.
     */
    protected boolean isSkippableRow(String dateValue) {
        return false;
    }

    /**
     * Determines whether a line is the CSV header line. Default implementation uses contains().
     * Schwab overrides this to use startsWith() for stricter matching.
     */
    protected boolean isHeaderLine(String line) {
        return line.contains(getHeaderMarker());
    }

    String skipPreamble(BufferedReader reader) throws IOException {
        var sb = new StringBuilder();
        String line;
        boolean foundHeader = false;

        while ((line = reader.readLine()) != null) {
            if (!foundHeader) {
                if (isHeaderLine(line)) {
                    foundHeader = true;
                    sb.append(line).append('\n');
                }
            } else {
                sb.append(line).append('\n');
            }
        }

        return sb.toString();
    }

    protected BigDecimal parseAmount(String raw) {
        var cleaned = raw.replace("$", "").replace(",", "").replace(" ", "").trim();
        return new BigDecimal(cleaned);
    }

    /**
     * Parses the value as an amount if non-blank, otherwise returns null.
     */
    protected BigDecimal parseOptionalAmount(String value) {
        return (value != null && !value.isBlank()) ? parseAmount(value) : null;
    }

    /**
     * Returns the symbol if non-blank, otherwise returns null.
     */
    protected String parseOptionalSymbol(String symbol) {
        return (symbol != null && !symbol.isBlank()) ? symbol : null;
    }

    protected LocalDate parseDate(String raw) {
        return LocalDate.parse(raw.trim(), getDateFormat());
    }

    /**
     * Maps an action/transaction-type string to a {@link TransactionType}: an exact (case-insensitive)
     * lookup in {@link #getActionMap()} first, falling back to sign-of-amount inference for entries in
     * {@link #getSignDependentActions()}. Returns null when the action is unrecognized.
     */
    protected TransactionType mapAction(String action, BigDecimal amount) {
        if (action == null) {
            return null;
        }
        var upper = action.trim().toUpperCase(Locale.US);

        var mapped = getActionMap().get(upper);
        if (mapped != null) {
            return mapped;
        }

        if (getSignDependentActions().contains(upper)) {
            return (amount != null && amount.compareTo(BigDecimal.ZERO) < 0)
                    ? TransactionType.WITHDRAWAL : TransactionType.DEPOSIT;
        }

        return null;
    }

    /**
     * Shared tail of {@code extractRow}: reads quantity (via {@link #getQuantityColumn()}) and
     * Symbol from the record, normalises {@code amount} to its absolute value, then appends a
     * {@link ParsedTransaction}. Call this after the broker-specific amount and type have been
     * resolved.
     */
    protected void addTransaction(LocalDate date, TransactionType type, BigDecimal amount,
                                   CSVRecord record,
                                   List<ParsedTransaction> transactions) {
        var quantity = parseOptionalAmount(record.get(getQuantityColumn()));
        var absAmount = amount != null ? amount.abs() : null;
        var parsedSymbol = parseOptionalSymbol(record.get("Symbol"));
        transactions.add(new ParsedTransaction(date, type, parsedSymbol, quantity, absAmount));
    }
}
