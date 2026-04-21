package com.wealthview.importmodule.csv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTransactionParserTest {

    private final CsvTransactionParser parser = new CsvTransactionParser();

    @Test
    void parse_validCsv_returnsTransactions() throws IOException {
        var csv = """
                date,type,symbol,quantity,amount
                2025-01-15,buy,AAPL,10,1500.00
                2025-01-20,sell,MSFT,5,2000.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(2);
        assertThat(result.errors()).isEmpty();
        assertThat(result.transactions().get(0).symbol()).isEqualTo("AAPL");
    }

    @Test
    void parse_missingDate_reportsError() throws IOException {
        var csv = """
                date,type,symbol,quantity,amount
                ,buy,AAPL,10,1500.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Missing date");
    }

    @Test
    void parse_invalidDate_reportsError() throws IOException {
        var csv = """
                date,type,symbol,quantity,amount
                not-a-date,buy,AAPL,10,1500.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Invalid date");
    }

    @Test
    void parse_invalidAmount_reportsError() throws IOException {
        var csv = """
                date,type,symbol,quantity,amount
                2025-01-15,buy,AAPL,10,not-a-number
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Invalid amount");
    }

    @Test
    void parse_emptyCsv_returnsEmpty() throws IOException {
        var csv = """
                date,type,symbol,quantity,amount
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_mixedValidAndInvalid_returnsBoth() throws IOException {
        var csv = """
                date,type,symbol,quantity,amount
                2025-01-15,buy,AAPL,10,1500.00
                bad-date,sell,MSFT,5,2000.00
                2025-01-20,deposit,,, 3000.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(2);
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void parse_depositWithNoSymbol_parsesSuccessfully() throws IOException {
        var csv = """
                date,type,symbol,quantity,amount
                2025-01-15,deposit,,,5000.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).symbol()).isNull();
        assertThat(result.transactions().get(0).quantity()).isNull();
    }
}
