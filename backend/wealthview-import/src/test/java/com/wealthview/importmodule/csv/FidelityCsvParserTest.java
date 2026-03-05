package com.wealthview.importmodule.csv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FidelityCsvParserTest {

    private final FidelityCsvParser parser = new FidelityCsvParser();

    @Test
    void parse_validFidelityCsv_extractsAllTransactionTypes() throws IOException {
        var input = new InputStreamReader(
                getClass().getResourceAsStream("/fidelity-sample.csv"), StandardCharsets.UTF_8);

        var result = parser.parse(input);

        assertThat(result.transactions()).hasSize(7);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_skipsNonCsvPreamble_findsHeaderRow() throws IOException {
        var csv = """
                Brokerage

                Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date
                01/15/2025,YOU BOUGHT,AAPL,APPLE INC,Cash,10,$185.50,$0.00,$0.00,,-$1855.00,$5000.00,01/17/2025
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.transactions().get(0).type()).isEqualTo("buy");
    }

    @Test
    void parse_handlesReinvestment_mapsToBuy() throws IOException {
        var csv = """
                Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date
                02/01/2025,REINVESTMENT,VTI,VANGUARD TOTAL STK MKT,Cash,0.5,$250.00,$0.00,$0.00,,-$125.00,$6999.50,02/03/2025
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("buy");
        assertThat(result.transactions().get(0).symbol()).isEqualTo("VTI");
        assertThat(result.transactions().get(0).quantity()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void parse_handlesDollarSignsAndCommas_parsesAmounts() throws IOException {
        var csv = """
                Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date
                03/01/2025,YOU BOUGHT,GOOG,ALPHABET INC CL A,Cash,3,"$1,450.00",$0.00,$0.00,,-$4350.00,$4649.50,03/03/2025
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("4350.00"));
    }

    @Test
    void parse_handlesElectronicTransfer_detectsDepositOrWithdrawal() throws IOException {
        var csv = """
                Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date
                02/10/2025,ELECTRONIC FUNDS TRANSFER,,TRANSFERRED FROM BANK,,,,,,,$5000.00,$11999.50,
                02/15/2025,ELECTRONIC FUNDS TRANSFER,,TRANSFERRED TO BANK,,,,,,,- $3000.00,$8999.50,
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(2);
        assertThat(result.transactions().get(0).type()).isEqualTo("deposit");
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.transactions().get(1).type()).isEqualTo("withdrawal");
        assertThat(result.transactions().get(1).amount()).isEqualByComparingTo(new BigDecimal("3000.00"));
    }

    @Test
    void parse_dividend_setsNullQuantity() throws IOException {
        var csv = """
                Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date
                01/25/2025,DIVIDEND RECEIVED,AAPL,APPLE INC,Cash,,,,,,$24.50,$7124.50,
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo("dividend");
        assertThat(result.transactions().get(0).quantity()).isNull();
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("24.50"));
    }

    @Test
    void parse_unknownAction_skipsWithError() throws IOException {
        var csv = """
                Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date
                01/15/2025,FOREIGN TAX WITHHELD,AAPL,APPLE INC,Cash,,,,,,-$3.50,$4996.50,
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Unknown action");
    }

    @Test
    void parse_youBought_normalizesAmountToPositive() throws IOException {
        var csv = """
                Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date
                01/15/2025,YOU BOUGHT,AAPL,APPLE INC,Cash,10,$185.50,$0.00,$0.00,,-$1855.00,$5000.00,01/17/2025
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("1855.00"));
    }
}
