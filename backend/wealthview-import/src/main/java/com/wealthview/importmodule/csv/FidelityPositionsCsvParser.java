package com.wealthview.importmodule.csv;

import com.wealthview.core.importservice.CsvParser;
import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component("fidelityPositionsCsvParser")
public class FidelityPositionsCsvParser implements CsvParser {

    private static final Logger log = LoggerFactory.getLogger(FidelityPositionsCsvParser.class);
    private static final String HEADER_MARKER = "Account Number";
    private static final Pattern DATE_PATTERN = Pattern.compile("Date downloaded\\s+(\\w{3}-\\d{2}-\\d{4})");
    private static final DateTimeFormatter FOOTER_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM-dd-yyyy", Locale.US);

    @Override
    public CsvParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public CsvParseResult parse(Reader reader) throws IOException {
        var buffered = new BufferedReader(reader);
        var lines = readAllLines(buffered);

        var csvContent = extractCsvContent(lines);
        if (csvContent.isEmpty()) {
            return new CsvParseResult(new ArrayList<>(), new ArrayList<>());
        }

        var snapshotDate = extractDateFromFooter(lines);

        var transactions = new ArrayList<ParsedTransaction>();
        var errors = new ArrayList<CsvRowError>();

        var format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .setTrailingDelimiter(true)
                .setIgnoreHeaderCase(true)
                .build();

        try (var parser = new CSVParser(new StringReader(csvContent), format)) {
            int rowNum = 1;
            for (var record : parser) {
                rowNum++;
                try {
                    var symbol = record.get("Symbol");
                    var description = record.get("Description");

                    if (symbol == null || symbol.isBlank()) {
                        continue;
                    }
                    if (symbol.startsWith("Pending")) {
                        continue;
                    }
                    if (description != null && description.contains("HELD IN FCASH")) {
                        var currentValueStr = record.get("Current Value");
                        if (currentValueStr != null && !currentValueStr.isBlank()) {
                            BigDecimal cashAmount = parseAmount(currentValueStr);
                            transactions.add(new ParsedTransaction(snapshotDate, "deposit", null, null, cashAmount));
                        }
                        continue;
                    }

                    var quantityStr = record.get("Quantity");
                    var costBasisStr = record.get("Cost Basis Total");

                    BigDecimal quantity = parseAmount(quantityStr);
                    BigDecimal costBasis;

                    if (costBasisStr == null || costBasisStr.isBlank() || costBasisStr.equals("--")) {
                        costBasis = quantity.multiply(BigDecimal.ONE);
                    } else {
                        costBasis = parseAmount(costBasisStr);
                    }

                    transactions.add(new ParsedTransaction(snapshotDate, "opening_balance", symbol, quantity, costBasis));

                } catch (Exception e) {
                    errors.add(new CsvRowError(rowNum, "Error parsing row: " + e.getMessage()));
                }
            }
        }

        return new CsvParseResult(transactions, errors);
    }

    private List<String> readAllLines(BufferedReader reader) throws IOException {
        var lines = new ArrayList<String>();
        String line;
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            line = line.replace("\r", "");
            if (first) {
                line = stripBom(line);
                first = false;
            }
            lines.add(line);
        }
        return lines;
    }

    private String stripBom(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    private String extractCsvContent(List<String> lines) {
        var sb = new StringBuilder();
        boolean foundHeader = false;

        for (var line : lines) {
            if (!foundHeader) {
                if (line.startsWith(HEADER_MARKER)) {
                    foundHeader = true;
                    sb.append(line).append('\n');
                }
            } else {
                if (line.isBlank()) {
                    break;
                }
                sb.append(line).append('\n');
            }
        }

        return sb.toString();
    }

    LocalDate extractDateFromFooter(List<String> lines) {
        for (var rawLine : lines) {
            var line = rawLine.replace("\"", "");
            var matcher = DATE_PATTERN.matcher(line);
            if (matcher.find()) {
                try {
                    return LocalDate.parse(matcher.group(1), FOOTER_DATE_FORMAT);
                } catch (DateTimeParseException e) {
                    log.warn("Could not parse date from footer: {}", matcher.group(1));
                }
            }
        }
        log.warn("No date found in footer, falling back to today");
        return LocalDate.now();
    }

    private BigDecimal parseAmount(String raw) {
        var cleaned = raw.replace("$", "").replace(",", "").replace("+", "").replace("\"", "").trim();
        return new BigDecimal(cleaned);
    }
}
