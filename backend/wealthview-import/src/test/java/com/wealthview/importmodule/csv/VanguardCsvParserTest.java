package com.wealthview.importmodule.csv;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.wealthview.persistence.entity.TransactionType;

import static com.wealthview.persistence.entity.TransactionType.BUY;
import static com.wealthview.persistence.entity.TransactionType.WITHDRAWAL;
import static org.assertj.core.api.Assertions.assertThat;

class VanguardCsvParserTest {

    private static final String HEADER =
            "Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount";

    private final VanguardCsvParser parser = new VanguardCsvParser();

    /**
     * Builds a single unquoted-by-default Vanguard CSV data row. Callers pass fields already
     * quoted (embedded double quotes) where the original fixture needed to escape a comma,
     * e.g. {@code "\"$1,250.00\""}.
     */
    private static String row(String date, String type, String investmentName, String symbol,
                               String shares, String sharePrice, String netAmount) {
        return String.join(",", date, type, investmentName, symbol, shares, sharePrice, netAmount);
    }

    /**
     * Builds a complete single-row Vanguard CSV: the header line followed by one data row.
     */
    private static String csvWithRow(String date, String type, String investmentName, String symbol,
                                      String shares, String sharePrice, String netAmount) {
        return HEADER + "\n" + row(date, type, investmentName, symbol, shares, sharePrice, netAmount) + "\n";
    }

    @Test
    void parse_validVanguardCsv_extractsAllTransactionTypes() throws IOException {
        var input = new InputStreamReader(
                getClass().getResourceAsStream("/vanguard-sample.csv"), StandardCharsets.UTF_8);

        var result = parser.parse(input);

        assertThat(result.transactions()).hasSize(10);
        assertThat(result.errors()).isEmpty();
        assertThat(result.transactions().get(0).type()).isEqualTo(BUY);
        assertThat(result.transactions().get(0).symbol()).isEqualTo("VTSAX");
        assertThat(result.transactions().get(7).type()).isEqualTo(WITHDRAWAL);
    }

    /**
     * Pins the transaction-type to {@link TransactionType} mapping family driven by
     * {@link VanguardCsvParser}'s {@code ACTION_MAP}. Unlike Schwab, none of Vanguard's actions
     * are sign-dependent (Vanguard doesn't override {@code getSignDependentActions()}), so every
     * "_mapsTo_" case belongs in this single family table.
     */
    @ParameterizedTest(name = "[{index}] {1} -> {7}")
    @CsvSource(textBlock = """
            01/10/2025, Buy, VANGUARD TOTAL STOCK MKT IDX ADM, VTSAX, 5.000, $250.00, '"$1,250.00"', BUY, 5.000
            01/20/2025, Sell, APPLE INC, AAPL, 10.000, $195.50, '"$1,955.00"', SELL, 10.000
            01/25/2025, Dividend, APPLE INC, AAPL, '', '', $48.50, DIVIDEND,
            02/01/2025, Reinvestment, VANGUARD TOTAL STOCK MKT IDX ADM, VTSAX, 0.200, $250.00, $50.00, BUY, 0.200
            02/15/2025, 'Capital gain (LT)', VANGUARD 500 INDEX ADMIRAL, VFIAX, '', '', $125.75, DIVIDEND,
            02/20/2025, 'Capital gain (ST)', VANGUARD 500 INDEX ADMIRAL, VFIAX, '', '', $32.10, DIVIDEND,
            03/01/2025, 'Transfer (incoming)', Transfer from bank, '', '', '', '"$5,000.00"', DEPOSIT,
            03/10/2025, 'Transfer (outgoing)', Transfer to bank, '', '', '', '"$2,000.00"', WITHDRAWAL,
            03/15/2025, 'Sweep in', VANGUARD FEDERAL MONEY MARKET, VMFXX, '', '', $500.00, DEPOSIT,
            03/20/2025, 'Sweep out', VANGUARD FEDERAL MONEY MARKET, VMFXX, '', '', $300.00, WITHDRAWAL,
            """)
    void parse_transactionType_mapsToTransactionType(
            String date, String type, String investmentName, String symbol, String shares,
            String sharePrice, String netAmount, String expectedType, String expectedQuantity) throws IOException {
        var csv = csvWithRow(date, type, investmentName, symbol, shares, sharePrice, netAmount);

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.type()).isEqualTo(TransactionType.valueOf(expectedType));
        if (expectedQuantity == null) {
            assertThat(txn.quantity()).isNull();
        } else {
            assertThat(txn.quantity()).isEqualByComparingTo(new BigDecimal(expectedQuantity));
        }
    }

    @Test
    void parse_dateFormat_parsesMmDdYyyy() throws IOException {
        var csv = csvWithRow("12/31/2025", "Buy", "TEST FUND", "TEST", "1.000", "$100.00", "$100.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void parse_handlesAmountWithDollarSignsAndCommas() throws IOException {
        var csv = csvWithRow("01/10/2025", "Buy", "TEST FUND", "TEST", "10.000", "\"$1,250.50\"", "\"$12,505.00\"");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("12505.00"));
    }

    @Test
    void parse_unknownTransactionType_skipsWithError() throws IOException {
        var csv = csvWithRow("01/10/2025", "Fee Charged", "ACCOUNT FEE", "", "", "", "$25.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Unknown transaction type");
    }

    @Test
    void parse_emptyFile_returnsEmptyResult() throws IOException {
        var result = parser.parse(new StringReader(""));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_invalidDate_skipsRowWithError() throws IOException {
        var csv = csvWithRow("not-a-date", "Buy", "TEST FUND", "TEST", "1.000", "$100.00", "$100.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Invalid date");
    }

    @Test
    void parse_blankDateRow_skipped() throws IOException {
        var csv = csvWithRow("", "Buy", "TEST FUND", "TEST", "1.000", "$100.00", "$100.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_malformedAmount_skipsRowWithError() throws IOException {
        var csv = csvWithRow("01/10/2025", "Buy", "TEST FUND", "TEST", "abc", "$100.00", "$100.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Error parsing row");
    }

    /**
     * Verifies that when a row is malformed in both transaction type (unknown token)
     * and amount (non-numeric), the error reported is the generic row-level parse error.
     * This pins the shared row template's precedence: amount parsing occurs before action mapping.
     * Fidelity and Schwab already followed this ordering; Vanguard adopted it in the
     * Template Method consolidation.
     */
    @Test
    void parse_unknownTypeAndMalformedAmount_reportsGenericRowError() throws IOException {
        var csv = csvWithRow("01/10/2025", "Teleport", "TEST FUND", "TEST", "1.000", "$100.00", "abc");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).startsWith("Error parsing row");
    }

    @Test
    void parse_preambleBeforeHeader_skipsNonHeaderLines() throws IOException {
        var csv = "This is a preamble line from Vanguard\n"
                + "Another junk line with account info\n"
                + csvWithRow("01/10/2025", "Buy", "TEST FUND", "TEST", "1.000", "$100.00", "$100.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.errors()).isEmpty();
        assertThat(result.transactions().get(0).type()).isEqualTo(BUY);
        assertThat(result.transactions().get(0).symbol()).isEqualTo("TEST");
    }
}
