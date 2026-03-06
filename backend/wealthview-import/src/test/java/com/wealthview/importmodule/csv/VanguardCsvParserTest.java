package com.wealthview.importmodule.csv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class VanguardCsvParserTest {

    private final VanguardCsvParser parser = new VanguardCsvParser();

    @Test
    void parse_validVanguardCsv_extractsAllTransactionTypes() throws IOException {
        var input = new InputStreamReader(
                getClass().getResourceAsStream("/vanguard-sample.csv"), StandardCharsets.UTF_8);

        var result = parser.parse(input);

        assertThat(result.transactions()).hasSize(10);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_buyTransaction_mapsToBuy() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                01/10/2025,Buy,VANGUARD TOTAL STOCK MKT IDX ADM,VTSAX,5.000,$250.00,"$1,250.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("buy");
        assertThat(result.transactions().get(0).symbol()).isEqualTo("VTSAX");
        assertThat(result.transactions().get(0).quantity()).isEqualByComparingTo(new BigDecimal("5.000"));
    }

    @Test
    void parse_sellTransaction_mapsToSell() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                01/20/2025,Sell,APPLE INC,AAPL,10.000,$195.50,"$1,955.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("sell");
    }

    @Test
    void parse_dividendTransaction_mapsToDividend() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                01/25/2025,Dividend,APPLE INC,AAPL,,,$48.50
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("dividend");
        assertThat(result.transactions().get(0).quantity()).isNull();
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("48.50"));
    }

    @Test
    void parse_reinvestment_mapsToBuy() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                02/01/2025,Reinvestment,VANGUARD TOTAL STOCK MKT IDX ADM,VTSAX,0.200,$250.00,$50.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("buy");
        assertThat(result.transactions().get(0).quantity()).isEqualByComparingTo(new BigDecimal("0.200"));
    }

    @Test
    void parse_capitalGainLongTerm_mapsToDividend() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                02/15/2025,Capital gain (LT),VANGUARD 500 INDEX ADMIRAL,VFIAX,,,$125.75
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("dividend");
        assertThat(result.transactions().get(0).symbol()).isEqualTo("VFIAX");
    }

    @Test
    void parse_capitalGainShortTerm_mapsToDividend() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                02/20/2025,Capital gain (ST),VANGUARD 500 INDEX ADMIRAL,VFIAX,,,$32.10
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("dividend");
    }

    @Test
    void parse_transferIn_mapsToDeposit() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                03/01/2025,Transfer (incoming),Transfer from bank,,,,"$5,000.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("deposit");
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    void parse_transferOut_mapsToWithdrawal() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                03/10/2025,Transfer (outgoing),Transfer to bank,,,,"$2,000.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("withdrawal");
    }

    @Test
    void parse_dateFormat_parsesMmDdYyyy() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                12/31/2025,Buy,TEST FUND,TEST,1.000,$100.00,$100.00
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void parse_handlesAmountWithDollarSignsAndCommas() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                01/10/2025,Buy,TEST FUND,TEST,10.000,"$1,250.50","$12,505.00"
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("12505.00"));
    }

    @Test
    void parse_unknownTransactionType_skipsWithError() throws IOException {
        var csv = """
                Trade Date,Transaction Type,Investment Name,Symbol,Shares,Share Price,Net Amount
                01/10/2025,Fee Charged,ACCOUNT FEE,,,,$25.00
                """;

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
}
