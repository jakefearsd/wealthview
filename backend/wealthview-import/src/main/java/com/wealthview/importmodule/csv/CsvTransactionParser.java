package com.wealthview.importmodule.csv;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.stereotype.Component;

import com.wealthview.core.importservice.ImportParser;
import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ImportParseResult;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import com.wealthview.persistence.entity.TransactionType;

@Component
@org.springframework.context.annotation.Primary
public class CsvTransactionParser implements ImportParser {

    @Override
    public ImportParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    public ImportParseResult parse(Reader reader) throws IOException {
        var transactions = new ArrayList<ParsedTransaction>();
        var errors = new ArrayList<CsvRowError>();

        var format = CSVFormat.DEFAULT.builder()
                .setHeader("date", "type", "symbol", "quantity", "amount")
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();

        try (var parser = new CSVParser(reader, format)) {
            int rowNum = 1;
            for (var record : parser) {
                rowNum++;
                try {
                    var dateStr = record.get("date");
                    var typeStr = record.get("type");
                    var symbol = record.get("symbol");
                    var quantityStr = record.get("quantity");
                    var amountStr = record.get("amount");

                    if (dateStr.isBlank()) {
                        errors.add(new CsvRowError(rowNum, "Missing date"));
                        continue;
                    }

                    LocalDate date;
                    try {
                        date = LocalDate.parse(dateStr);
                    } catch (DateTimeParseException e) {
                        errors.add(new CsvRowError(rowNum, "Invalid date format: " + dateStr));
                        continue;
                    }

                    // TransactionType.fromValue is the single source of truth for the closed set
                    // (it is case-tolerant, so the former toLowerCase is no longer needed). An
                    // unknown token stays a per-row error rather than failing the whole file.
                    TransactionType type;
                    try {
                        type = TransactionType.fromValue(typeStr);
                    } catch (IllegalArgumentException e) {
                        errors.add(new CsvRowError(rowNum, "Invalid transaction type: " + typeStr));
                        continue;
                    }

                    BigDecimal amount;
                    try {
                        amount = new BigDecimal(amountStr);
                    } catch (NumberFormatException e) {
                        errors.add(new CsvRowError(rowNum, "Invalid amount: " + amountStr));
                        continue;
                    }

                    BigDecimal quantity = null;
                    if (quantityStr != null && !quantityStr.isBlank()) {
                        try {
                            quantity = new BigDecimal(quantityStr);
                        } catch (NumberFormatException e) {
                            errors.add(new CsvRowError(rowNum, "Invalid quantity: " + quantityStr));
                            continue;
                        }
                    }

                    var parsedSymbol = (symbol != null && !symbol.isBlank()) ? symbol : null;
                    transactions.add(new ParsedTransaction(date, type, parsedSymbol, quantity, amount));

                } catch (DateTimeParseException | IllegalArgumentException e) {
                    errors.add(new CsvRowError(rowNum, "Error parsing row: " + e.getMessage()));
                }
            }
        }

        return new ImportParseResult(transactions, errors);
    }
}
