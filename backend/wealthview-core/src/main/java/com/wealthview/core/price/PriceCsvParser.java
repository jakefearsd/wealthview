package com.wealthview.core.price;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class PriceCsvParser {

    public record PriceRow(String symbol, LocalDate date, BigDecimal closePrice) {
    }

    public record PriceCsvParseResult(List<PriceRow> rows, List<String> errors) {
    }

    public PriceCsvParseResult parse(InputStream inputStream) throws IOException {
        var errors = new ArrayList<String>();
        var rowsByKey = new LinkedHashMap<String, PriceRow>();

        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            boolean headerSkipped = false;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                var trimmed = line.trim();

                if (trimmed.isEmpty()) {
                    continue;
                }

                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }

                parseLine(trimmed, lineNumber, rowsByKey, errors);
            }
        }

        return new PriceCsvParseResult(new ArrayList<>(rowsByKey.values()), errors);
    }

    private void parseLine(String line, int lineNumber, LinkedHashMap<String, PriceRow> rowsByKey,
                           List<String> errors) {
        var parts = line.split(",", -1);
        if (parts.length < 3) {
            errors.add("Line " + lineNumber + ": expected at least 3 columns, got " + parts.length);
            return;
        }

        var symbol = parts[0].trim().toUpperCase();
        if (symbol.isEmpty()) {
            errors.add("Line " + lineNumber + ": missing symbol");
            return;
        }

        LocalDate date;
        try {
            date = LocalDate.parse(parts[1].trim());
        } catch (DateTimeParseException e) {
            errors.add("Line " + lineNumber + ": invalid date format (expected YYYY-MM-DD)");
            return;
        }

        if (date.isAfter(LocalDate.now())) {
            errors.add("Line " + lineNumber + ": future date not allowed");
            return;
        }

        BigDecimal price;
        try {
            price = new BigDecimal(parts[2].trim());
        } catch (NumberFormatException e) {
            errors.add("Line " + lineNumber + ": invalid price format");
            return;
        }

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Line " + lineNumber + ": price must be greater than zero");
            return;
        }

        var key = symbol + "|" + date;
        rowsByKey.put(key, new PriceRow(symbol, date, price));
    }
}
