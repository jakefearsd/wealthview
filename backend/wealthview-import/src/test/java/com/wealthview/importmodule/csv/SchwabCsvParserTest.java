package com.wealthview.importmodule.csv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SchwabCsvParserTest {

    private final SchwabCsvParser parser = new SchwabCsvParser();

    @Test
    void parse_validSchwabCsv_extractsAllTransactionTypes() throws IOException {
        var input = new InputStreamReader(
                getClass().getResourceAsStream("/schwab-sample.csv"), StandardCharsets.UTF_8);

        var result = parser.parse(input);

        assertThat(result.transactions()).hasSize(8);
        assertThat(result.errors()).isEmpty();
        assertThat(result.transactions().get(0).type()).isEqualTo("buy");
        assertThat(result.transactions().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.transactions().get(2).type()).isEqualTo("dividend");
        assertThat(result.transactions().get(6).type()).isEqualTo("deposit");
    }

    @Test
    void parse_skipsPreamble_findsHeaderRow() throws IOException {
        var csv = """
                "Transactions for account ending in ...1234 as of 03/06/2026"
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/10/2025","Buy","AAPL","APPLE INC","10","$195.50","","-$1,955.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.symbol()).isEqualTo("AAPL");
        assertThat(txn.type()).isEqualTo("buy");
        assertThat(txn.date()).isEqualTo(LocalDate.of(2025, 1, 10));
    }

    @Test
    void parse_buyAction_mapsToBuy() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/10/2025","Buy","AAPL","APPLE INC","10","$195.50","","-$1,955.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("buy");
        assertThat(result.transactions().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.transactions().get(0).quantity()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void parse_sellAction_mapsToSell() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/15/2025","Sell","MSFT","MICROSOFT CORP","5","$420.00","","$2,100.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.type()).isEqualTo("sell");
        assertThat(txn.date()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(txn.symbol()).isEqualTo("MSFT");
        assertThat(txn.quantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(txn.amount()).isEqualByComparingTo(new BigDecimal("2100.00"));
    }

    @Test
    void parse_buyAmount_normalizedToPositive() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/10/2025","Buy","AAPL","APPLE INC","10","$195.50","","-$1,955.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("1955.00"));
    }

    @Test
    void parse_qualifiedDividend_mapsToDividend() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/20/2025","Qualified Dividend","AAPL","APPLE INC","","","","$24.50"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("dividend");
        assertThat(result.transactions().get(0).quantity()).isNull();
    }

    @Test
    void parse_cashDividend_mapsToDividend() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/20/2025","Cash Dividend","AAPL","APPLE INC","","","","$24.50"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.type()).isEqualTo("dividend");
        assertThat(txn.amount()).isEqualByComparingTo(new BigDecimal("24.50"));
        assertThat(txn.quantity()).isNull();
        assertThat(txn.symbol()).isEqualTo("AAPL");
    }

    @Test
    void parse_reinvestDividend_mapsToBuy() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "02/01/2025","Reinvest Dividend","VTI","VANGUARD TOTAL STOCK MARKET ETF","0.5","$250.00","","-$125.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("buy");
        assertThat(result.transactions().get(0).quantity()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void parse_longTermCapGainReinvest_mapsToBuy() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "02/15/2025","Long Term Cap Gain Reinvest","VTI","VANGUARD TOTAL STOCK MARKET ETF","0.1","$252.00","","-$25.20"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.type()).isEqualTo("buy");
        assertThat(txn.quantity()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(txn.amount()).isEqualByComparingTo(new BigDecimal("25.20"));
    }

    @Test
    void parse_bankInterest_mapsToDividend() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "02/10/2025","Bank Interest","","BANK INT 011025-020925","","","","$3.75"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.type()).isEqualTo("dividend");
        assertThat(txn.amount()).isEqualByComparingTo(new BigDecimal("3.75"));
        assertThat(txn.symbol()).isNull();
    }

    @Test
    void parse_moneyLinkTransferDeposit_mapsToDeposit() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "03/01/2025","MoneyLink Transfer","","TRANSFER FROM BANK","","","","$5,000.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("deposit");
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    void parse_moneyLinkTransferWithdrawal_mapsToWithdrawal() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "03/10/2025","MoneyLink Transfer","","TRANSFER TO BANK","","","","-$2,000.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("withdrawal");
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void parse_totalRow_skipped() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/10/2025","Buy","AAPL","APPLE INC","10","$195.50","","-$1,955.00"
                "Transactions Total","","","","","","","$5,022.05"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_unknownAction_skipsWithError() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/10/2025","Foreign Tax Withheld","AAPL","APPLE INC","","","","-$3.50"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Unknown action");
    }

    @Test
    void parse_dateFormat_parsesMmDdYyyy() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "12/31/2025","Buy","TEST","TEST FUND","1","$100.00","","-$100.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void parse_amountWithCommasAndDollarSign() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/10/2025","Sell","AAPL","APPLE INC","10","$195.50","","$12,505.00"
                """;

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
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "bad-date","Buy","AAPL","APPLE INC","10","$195.50","","-$1,955.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_blankDateRow_skipped() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "","Buy","AAPL","APPLE INC","10","$195.50","","-$1,955.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_malformedAmount_skipsRowWithError() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "01/10/2025","Buy","AAPL","APPLE INC","10","$195.50","","abc"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Error parsing row");
    }

    @Test
    void parse_wireFundsDeposit_mapsToDeposit() throws IOException {
        var csv = """
                "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
                "03/05/2025","Wire Funds","","WIRE TRANSFER RECEIVED","","","","$10,000.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("deposit");
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("10000.00"));
    }
}
