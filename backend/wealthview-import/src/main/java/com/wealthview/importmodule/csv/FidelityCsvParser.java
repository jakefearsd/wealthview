package com.wealthview.importmodule.csv;

import com.wealthview.core.importservice.CsvParser;
import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.stereotype.Component;

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
import java.util.Map;

@Component("fidelityCsvParser")
public class FidelityCsvParser implements CsvParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String HEADER_MARKER = "Run Date";
    private static final Map<String, String> ACTION_MAP = Map.of(
            "YOU BOUGHT", "buy",
            "REINVESTMENT", "buy",
            "YOU SOLD", "sell",
            "DIVIDEND RECEIVED", "dividend"
    );

    @Override
    public CsvParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    public CsvParseResult parse(Reader reader) throws IOException {
        var buffered = new BufferedReader(reader);
        var csvContent = skipPreamble(buffered);

        var transactions = new ArrayList<ParsedTransaction>();
        var errors = new ArrayList<CsvRowError>();

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
                    var dateStr = record.get("Run Date");
                    var action = record.get("Action");
                    var symbol = record.get("Symbol");
                    var quantityStr = record.get("Quantity");
                    var amountStr = record.get("Amount ($)");

                    if (dateStr == null || dateStr.isBlank()) {
                        continue;
                    }

                    LocalDate date;
                    try {
                        date = parseDate(dateStr);
                    } catch (DateTimeParseException e) {
                        errors.add(new CsvRowError(rowNum, "Invalid date: " + dateStr));
                        continue;
                    }

                    BigDecimal amount = null;
                    if (amountStr != null && !amountStr.isBlank()) {
                        amount = parseAmount(amountStr);
                    }

                    var type = mapAction(action, amount);
                    if (type == null) {
                        errors.add(new CsvRowError(rowNum, "Unknown action: " + action));
                        continue;
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

                } catch (Exception e) {
                    errors.add(new CsvRowError(rowNum, "Error parsing row: " + e.getMessage()));
                }
            }
        }

        return new CsvParseResult(transactions, errors);
    }

    String skipPreamble(BufferedReader reader) throws IOException {
        var sb = new StringBuilder();
        String line;
        boolean foundHeader = false;

        while ((line = reader.readLine()) != null) {
            if (!foundHeader) {
                if (line.contains(HEADER_MARKER)) {
                    foundHeader = true;
                    sb.append(line).append("\n");
                }
            } else {
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    String mapAction(String action, BigDecimal amount) {
        if (action == null) return null;
        var upper = action.trim().toUpperCase();

        var mapped = ACTION_MAP.get(upper);
        if (mapped != null) {
            return mapped;
        }

        if ("ELECTRONIC FUNDS TRANSFER".equals(upper)) {
            return (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) ? "withdrawal" : "deposit";
        }

        return null;
    }

    BigDecimal parseAmount(String raw) {
        var cleaned = raw.replace("$", "").replace(",", "").replace(" ", "").trim();
        return new BigDecimal(cleaned);
    }

    LocalDate parseDate(String raw) {
        return LocalDate.parse(raw.trim(), DATE_FORMAT);
    }
}
