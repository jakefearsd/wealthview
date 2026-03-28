package com.wealthview.importmodule.csv;

import com.wealthview.core.importservice.dto.CsvRowError;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractBrokerCsvParserTest {

    /**
     * Minimal concrete subclass that exposes the protected helper methods for testing.
     * Uses a simple "Date,Type,Symbol,Amount" header and MM/dd/yyyy date format.
     */
    private static class TestParser extends AbstractBrokerCsvParser {

        private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        private static final String HEADER_MARKER = "Date";

        @Override
        protected void extractRow(CSVRecord record, int rowNum,
                                  List<ParsedTransaction> transactions, List<CsvRowError> errors) {
            var dateStr = record.get("Date");
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

            var type = record.get("Type");
            var symbol = parseOptionalSymbol(record.get("Symbol"));
            var amount = parseOptionalAmount(record.get("Amount"));
            transactions.add(new ParsedTransaction(date, type, symbol, null, amount));
        }

        @Override
        protected String getHeaderMarker() {
            return HEADER_MARKER;
        }

        @Override
        protected DateTimeFormatter getDateFormat() {
            return DATE_FORMAT;
        }
    }

    /**
     * Subclass that deliberately throws from extractRow to test error collection.
     */
    private static class ThrowingParser extends AbstractBrokerCsvParser {

        private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

        @Override
        protected void extractRow(CSVRecord record, int rowNum,
                                  List<ParsedTransaction> transactions, List<CsvRowError> errors) {
            throw new RuntimeException("simulated failure on row " + rowNum);
        }

        @Override
        protected String getHeaderMarker() {
            return "Date";
        }

        @Override
        protected DateTimeFormatter getDateFormat() {
            return DATE_FORMAT;
        }
    }

    private final TestParser parser = new TestParser();

    // --- parseAmount tests ---

    @Test
    void parseAmount_normalNumber_returnsBigDecimal() {
        var result = parser.parseAmount("123.45");

        assertThat(result).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    @Test
    void parseAmount_withDollarSignAndCommas_stripsFormatting() {
        var result = parser.parseAmount("$1,234.56");

        assertThat(result).isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void parseAmount_negativeNumber_returnsNegativeBigDecimal() {
        var result = parser.parseAmount("-100.00");

        assertThat(result).isEqualByComparingTo(new BigDecimal("-100.00"));
    }

    @Test
    void parseAmount_negativeDollarSignWithCommas_parsesCorrectly() {
        var result = parser.parseAmount("-$2,500.75");

        assertThat(result).isEqualByComparingTo(new BigDecimal("-2500.75"));
    }

    @Test
    void parseAmount_withSpaces_stripsWhitespace() {
        var result = parser.parseAmount(" $ 1,000.00 ");

        assertThat(result).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void parseAmount_nullInput_throwsNullPointerException() {
        assertThatThrownBy(() -> parser.parseAmount(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void parseAmount_emptyString_throwsNumberFormatException() {
        assertThatThrownBy(() -> parser.parseAmount(""))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void parseAmount_blankString_throwsNumberFormatException() {
        assertThatThrownBy(() -> parser.parseAmount("   "))
                .isInstanceOf(NumberFormatException.class);
    }

    // --- parseOptionalAmount tests ---

    @Test
    void parseOptionalAmount_validValue_returnsAmount() {
        var result = parser.parseOptionalAmount("$500.00");

        assertThat(result).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void parseOptionalAmount_nullValue_returnsNull() {
        var result = parser.parseOptionalAmount(null);

        assertThat(result).isNull();
    }

    @Test
    void parseOptionalAmount_blankValue_returnsNull() {
        var result = parser.parseOptionalAmount("   ");

        assertThat(result).isNull();
    }

    @Test
    void parseOptionalAmount_emptyString_returnsNull() {
        var result = parser.parseOptionalAmount("");

        assertThat(result).isNull();
    }

    // --- parseOptionalSymbol tests ---

    @Test
    void parseOptionalSymbol_nonBlank_returnsSymbol() {
        var result = parser.parseOptionalSymbol("AAPL");

        assertThat(result).isEqualTo("AAPL");
    }

    @Test
    void parseOptionalSymbol_null_returnsNull() {
        var result = parser.parseOptionalSymbol(null);

        assertThat(result).isNull();
    }

    @Test
    void parseOptionalSymbol_blank_returnsNull() {
        var result = parser.parseOptionalSymbol("  ");

        assertThat(result).isNull();
    }

    // --- parseDate tests ---

    @Test
    void parseDate_standardFormat_returnsLocalDate() {
        var result = parser.parseDate("03/15/2025");

        assertThat(result).isEqualTo(LocalDate.of(2025, 3, 15));
    }

    @Test
    void parseDate_withLeadingTrailingSpaces_trimsParsesCorrectly() {
        var result = parser.parseDate("  01/01/2024  ");

        assertThat(result).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void parseDate_invalidFormat_throwsDateTimeParseException() {
        assertThatThrownBy(() -> parser.parseDate("2025-03-15"))
                .isInstanceOf(DateTimeParseException.class);
    }

    // --- skipPreamble tests ---

    @Test
    void skipPreamble_withPreambleLines_skipsToHeader() throws IOException {
        var input = """
                Account Summary
                Some preamble text

                Date,Type,Symbol,Amount
                03/15/2025,buy,AAPL,$100.00
                """;

        var result = parser.skipPreamble(new BufferedReader(new StringReader(input)));

        assertThat(result).startsWith("Date,Type,Symbol,Amount");
        assertThat(result).contains("03/15/2025,buy,AAPL,$100.00");
        assertThat(result).doesNotContain("Account Summary");
        assertThat(result).doesNotContain("Some preamble text");
    }

    @Test
    void skipPreamble_noPreamble_returnsAllContent() throws IOException {
        var input = """
                Date,Type,Symbol,Amount
                03/15/2025,buy,AAPL,$100.00
                """;

        var result = parser.skipPreamble(new BufferedReader(new StringReader(input)));

        assertThat(result).startsWith("Date,Type,Symbol,Amount");
        assertThat(result).contains("03/15/2025,buy,AAPL,$100.00");
    }

    @Test
    void skipPreamble_noHeaderFound_returnsEmptyString() throws IOException {
        var input = """
                No header marker here
                Just some random text
                """;

        var result = parser.skipPreamble(new BufferedReader(new StringReader(input)));

        assertThat(result).isEmpty();
    }

    // --- Full parse integration tests ---

    @Test
    void parse_validCsvWithPreamble_parsesTransactions() throws IOException {
        var csv = """
                Brokerage Account
                Some extra info

                Date,Type,Symbol,Amount
                01/15/2025,buy,AAPL,"$1,500.00"
                02/20/2025,sell,GOOG,"$2,000.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(2);
        assertThat(result.errors()).isEmpty();

        var first = result.transactions().get(0);
        assertThat(first.date()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(first.type()).isEqualTo("buy");
        assertThat(first.symbol()).isEqualTo("AAPL");
        assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("1500.00"));

        var second = result.transactions().get(1);
        assertThat(second.date()).isEqualTo(LocalDate.of(2025, 2, 20));
        assertThat(second.type()).isEqualTo("sell");
        assertThat(second.symbol()).isEqualTo("GOOG");
        assertThat(second.amount()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void parse_blankContent_returnsEmptyResult() throws IOException {
        var csv = """
                No matching header
                Just preamble
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_extractRowThrowsException_collectsErrorAndContinues() throws IOException {
        var throwingParser = new ThrowingParser();
        var csv = """
                Date,Type,Symbol,Amount
                01/15/2025,buy,AAPL,$100.00
                02/20/2025,sell,GOOG,$200.00
                03/10/2025,buy,MSFT,$300.00
                """;

        var result = throwingParser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(3);
        assertThat(result.errors().get(0).rowNumber()).isEqualTo(2);
        assertThat(result.errors().get(0).message()).contains("Error parsing row");
        assertThat(result.errors().get(1).rowNumber()).isEqualTo(3);
        assertThat(result.errors().get(2).rowNumber()).isEqualTo(4);
    }

    @Test
    void parse_rowWithBlankDate_skippedSilently() throws IOException {
        var csv = """
                Date,Type,Symbol,Amount
                01/15/2025,buy,AAPL,$100.00
                ,,,
                02/20/2025,sell,GOOG,$200.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(2);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_rowWithInvalidDate_addsError() throws IOException {
        var csv = """
                Date,Type,Symbol,Amount
                01/15/2025,buy,AAPL,$100.00
                not-a-date,sell,GOOG,$200.00
                03/10/2025,buy,MSFT,$300.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(2);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Invalid date");
    }

    @Test
    void parse_emptySymbolField_returnsNullSymbol() throws IOException {
        var csv = """
                Date,Type,Symbol,Amount
                01/15/2025,deposit,,$500.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).symbol()).isNull();
    }

    @Test
    void parse_emptyAmountField_returnsNullAmount() throws IOException {
        var csv = """
                Date,Type,Symbol,Amount
                01/15/2025,fee,AAPL,
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).amount()).isNull();
    }
}
