package com.wealthview.importmodule.csv;

import com.wealthview.core.importservice.CsvParser;
import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Shared base class for broker-specific CSV transaction parsers.
 * Handles the common parse loop: preamble skipping, CSV parsing, row iteration with error collection.
 * Subclasses define column names, header detection, date format, and per-row extraction logic.
 */
public abstract class AbstractBrokerCsvParser implements CsvParser {

    @Override
    public CsvParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public CsvParseResult parse(Reader reader) throws IOException {
        var buffered = new BufferedReader(reader);
        var csvContent = skipPreamble(buffered);

        var transactions = new ArrayList<ParsedTransaction>();
        var errors = new ArrayList<CsvRowError>();

        if (csvContent.isBlank()) {
            return new CsvParseResult(transactions, errors);
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

        return new CsvParseResult(transactions, errors);
    }

    /**
     * Extract a single CSV row into a ParsedTransaction and add it to the transactions list,
     * or add a CsvRowError to the errors list if the row is invalid.
     * Rows that should be silently skipped (blank date, footer, unparseable date) may simply return
     * without adding to either list.
     */
    protected abstract void extractRow(CSVRecord record, int rowNum,
                                       List<ParsedTransaction> transactions, List<CsvRowError> errors);

    /**
     * Returns the header marker string used to detect where the CSV data begins.
     */
    protected abstract String getHeaderMarker();

    /**
     * Returns the DateTimeFormatter for parsing date strings in this broker's format.
     */
    protected abstract DateTimeFormatter getDateFormat();

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
}
