package com.wealthview.core.price;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PriceCsvParserTest {

    private PriceCsvParser parser;

    @BeforeEach
    void setUp() {
        parser = new PriceCsvParser();
    }

    @Test
    void parse_validCsvWithThreeRows_returnsAllRows() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02,185.50
                MSFT,2024-01-02,370.25
                GOOG,2024-01-02,140.10
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).hasSize(3);
        assertThat(result.errors()).isEmpty();

        assertThat(result.rows().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.rows().get(0).date()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(result.rows().get(0).closePrice()).isEqualByComparingTo("185.50");

        assertThat(result.rows().get(1).symbol()).isEqualTo("MSFT");
        assertThat(result.rows().get(2).symbol()).isEqualTo("GOOG");
    }

    @Test
    void parse_symbolWithWhitespaceAndLowercase_normalizedToUppercase() throws IOException {
        var csv = """
                symbol,date,close_price
                 aapl ,2024-01-02,185.50
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).symbol()).isEqualTo("AAPL");
    }

    @Test
    void parse_badDateFormat_collectsError() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,01/02/2024,185.50
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Line 2").contains("date");
    }

    @Test
    void parse_negativePrice_collectsError() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02,-5.00
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Line 2").contains("price");
    }

    @Test
    void parse_zeroPrice_collectsError() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02,0.00
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Line 2").contains("price");
    }

    @Test
    void parse_extraColumnsIgnored_parsesSuccessfully() throws IOException {
        var csv = """
                symbol,date,close_price,volume,open
                AAPL,2024-01-02,185.50,1000000,184.00
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows().get(0).closePrice()).isEqualByComparingTo("185.50");
    }

    @Test
    void parse_blankLinesSkipped() throws IOException {
        var csv = """
                symbol,date,close_price

                AAPL,2024-01-02,185.50

                MSFT,2024-01-02,370.25

                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).hasSize(2);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_emptyFile_returnsEmptyResult() throws IOException {
        var csv = "";

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_headerOnly_returnsEmptyResult() throws IOException {
        var csv = "symbol,date,close_price\n";

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_duplicateSymbolDate_lastOneWins() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02,185.50
                AAPL,2024-01-02,186.00
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows().get(0).closePrice()).isEqualByComparingTo("186.00");
    }

    @Test
    void parse_missingSymbol_collectsError() throws IOException {
        var csv = """
                symbol,date,close_price
                ,2024-01-02,185.50
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Line 2").contains("symbol");
    }

    @Test
    void parse_tooFewColumns_collectsError() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Line 2");
    }

    @Test
    void parse_invalidPriceFormat_collectsError() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02,abc
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Line 2").contains("price");
    }

    @Test
    void parse_futureDate_collectsError() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,2099-12-31,185.50
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Line 2").contains("future");
    }

    @Test
    void parse_mixedValidAndInvalidRows_returnsValidAndCollectsErrors() throws IOException {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02,185.50
                MSFT,bad-date,370.25
                GOOG,2024-01-02,140.10
                """;

        var result = parser.parse(toStream(csv));

        assertThat(result.rows()).hasSize(2);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.rows().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.rows().get(1).symbol()).isEqualTo("GOOG");
    }

    private ByteArrayInputStream toStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
