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
import static com.wealthview.persistence.entity.TransactionType.DEPOSIT;
import static com.wealthview.persistence.entity.TransactionType.DIVIDEND;
import static com.wealthview.persistence.entity.TransactionType.SELL;
import static com.wealthview.persistence.entity.TransactionType.WITHDRAWAL;
import static org.assertj.core.api.Assertions.assertThat;

class SchwabCsvParserTest {

    private static final String HEADER =
            "\"Date\",\"Action\",\"Symbol\",\"Description\",\"Quantity\",\"Price\",\"Fees & Comm\",\"Amount\"";

    private final SchwabCsvParser parser = new SchwabCsvParser();

    /**
     * Builds a single quoted Schwab CSV data row (the "Fees & Comm" column is always blank —
     * no fixture in this class needs a non-empty fee).
     */
    private static String row(String date, String action, String symbol, String description,
                               String quantity, String price, String amount) {
        return "\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"\",\"%s\""
                .formatted(date, action, symbol, description, quantity, price, amount);
    }

    /**
     * Builds a complete single-row Schwab CSV: the header line followed by one data row.
     */
    private static String csvWithRow(String date, String action, String symbol, String description,
                                      String quantity, String price, String amount) {
        return HEADER + "\n" + row(date, action, symbol, description, quantity, price, amount) + "\n";
    }

    @Test
    void parse_validSchwabCsv_extractsAllTransactionTypes() throws IOException {
        var input = new InputStreamReader(
                getClass().getResourceAsStream("/schwab-sample.csv"), StandardCharsets.UTF_8);

        var result = parser.parse(input);

        assertThat(result.transactions()).hasSize(8);
        assertThat(result.errors()).isEmpty();
        assertThat(result.transactions().get(0).type()).isEqualTo(BUY);
        assertThat(result.transactions().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.transactions().get(2).type()).isEqualTo(DIVIDEND);
        assertThat(result.transactions().get(6).type()).isEqualTo(DEPOSIT);
    }

    @Test
    void parse_skipsPreamble_findsHeaderRow() throws IOException {
        var csv = "\"Transactions for account ending in ...1234 as of 03/06/2026\"\n"
                + csvWithRow("01/10/2025", "Buy", "AAPL", "APPLE INC", "10", "$195.50", "-$1,955.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.symbol()).isEqualTo("AAPL");
        assertThat(txn.type()).isEqualTo(BUY);
        assertThat(txn.date()).isEqualTo(LocalDate.of(2025, 1, 10));
    }

    /**
     * Pins the action to {@link TransactionType} mapping family driven by
     * {@link SchwabCsvParser}'s {@code ACTION_MAP}. Each row is a distinct action string that
     * exact-matches an ACTION_MAP entry. The Schwab-specific sign-dependent actions
     * (MoneyLink Transfer, Wire Funds) are NOT part of this family — their type depends on the
     * sign of the amount rather than a fixed mapping, so they remain dedicated tests below.
     */
    @ParameterizedTest(name = "[{index}] {1} -> {7}")
    @CsvSource(textBlock = """
            01/10/2025, Buy, AAPL, APPLE INC, 10, $195.50, '-$1,955.00', BUY, 10
            01/15/2025, Sell, MSFT, MICROSOFT CORP, 5, $420.00, '$2,100.00', SELL, 5
            01/20/2025, 'Qualified Dividend', AAPL, APPLE INC, '', '', $24.50, DIVIDEND,
            01/20/2025, 'Cash Dividend', AAPL, APPLE INC, '', '', $24.50, DIVIDEND,
            02/01/2025, 'Reinvest Dividend', VTI, 'VANGUARD TOTAL STOCK', 0.5, $250.00, '-$125.00', BUY, 0.5
            02/15/2025, 'Long Term Cap Gain Reinvest', VTI, 'VANGUARD TOTAL STOCK', 0.1, $252.00, '-$25.20', BUY, 0.1
            02/10/2025, 'Bank Interest', '', 'BANK INT 011025-020925', '', '', $3.75, DIVIDEND,
            """)
    void parse_action_mapsToTransactionType(String date, String action, String symbol, String description,
                                             String quantity, String price, String amount,
                                             String expectedType, String expectedQuantity) throws IOException {
        var csv = csvWithRow(date, action, symbol, description, quantity, price, amount);

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
    void parse_buyAmount_normalizedToPositive() throws IOException {
        var csv = csvWithRow("01/10/2025", "Buy", "AAPL", "APPLE INC", "10", "$195.50", "-$1,955.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("1955.00"));
    }

    @Test
    void parse_moneyLinkTransferDeposit_mapsToDeposit() throws IOException {
        var csv = csvWithRow("03/01/2025", "MoneyLink Transfer", "", "TRANSFER FROM BANK", "", "", "$5,000.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo(DEPOSIT);
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    void parse_moneyLinkTransferWithdrawal_mapsToWithdrawal() throws IOException {
        var csv = csvWithRow("03/10/2025", "MoneyLink Transfer", "", "TRANSFER TO BANK", "", "", "-$2,000.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo(WITHDRAWAL);
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void parse_totalRow_skipped() throws IOException {
        var csv = csvWithRow("01/10/2025", "Buy", "AAPL", "APPLE INC", "10", "$195.50", "-$1,955.00")
                + row("Transactions Total", "", "", "", "", "", "$5,022.05") + "\n";

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_unknownAction_skipsWithError() throws IOException {
        var csv = csvWithRow("01/10/2025", "Foreign Tax Withheld", "AAPL", "APPLE INC", "", "", "-$3.50");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Unknown action");
    }

    @Test
    void parse_dateFormat_parsesMmDdYyyy() throws IOException {
        var csv = csvWithRow("12/31/2025", "Buy", "TEST", "TEST FUND", "1", "$100.00", "-$100.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void parse_amountWithCommasAndDollarSign() throws IOException {
        var csv = csvWithRow("01/10/2025", "Sell", "AAPL", "APPLE INC", "10", "$195.50", "$12,505.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("12505.00"));
    }

    @Test
    void parse_emptyFile_returnsEmptyResult() throws IOException {
        var result = parser.parse(new StringReader(""));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_invalidDate_skipsRowSilently() throws IOException {
        var csv = csvWithRow("bad-date", "Buy", "AAPL", "APPLE INC", "10", "$195.50", "-$1,955.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_blankDateRow_skipped() throws IOException {
        var csv = csvWithRow("", "Buy", "AAPL", "APPLE INC", "10", "$195.50", "-$1,955.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_malformedAmount_skipsRowWithError() throws IOException {
        var csv = csvWithRow("01/10/2025", "Buy", "AAPL", "APPLE INC", "10", "$195.50", "abc");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Error parsing row");
    }

    @Test
    void parse_wireFundsDeposit_mapsToDeposit() throws IOException {
        var csv = csvWithRow("03/05/2025", "Wire Funds", "", "WIRE TRANSFER RECEIVED", "", "", "$10,000.00");

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo(DEPOSIT);
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("10000.00"));
    }
}
