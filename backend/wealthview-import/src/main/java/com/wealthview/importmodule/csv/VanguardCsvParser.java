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

@Component("vanguardCsvParser")
public class VanguardCsvParser implements CsvParser {

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
    public CsvParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

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
                    var dateStr = record.get("Trade Date");
                    var transactionType = record.get("Transaction Type");
                    var symbol = record.get("Symbol");
                    var sharesStr = record.get("Shares");
                    var netAmountStr = record.get("Net Amount");

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

                    var type = mapAction(transactionType);
                    if (type == null) {
                        errors.add(new CsvRowError(rowNum, "Unknown transaction type: " + transactionType));
                        continue;
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

    String mapAction(String transactionType) {
        if (transactionType == null) return null;
        var upper = transactionType.trim().toUpperCase();
        return ACTION_MAP.get(upper);
    }

    BigDecimal parseAmount(String raw) {
        var cleaned = raw.replace("$", "").replace(",", "").replace(" ", "").trim();
        return new BigDecimal(cleaned);
    }

    LocalDate parseDate(String raw) {
        return LocalDate.parse(raw.trim(), DATE_FORMAT);
    }
}
