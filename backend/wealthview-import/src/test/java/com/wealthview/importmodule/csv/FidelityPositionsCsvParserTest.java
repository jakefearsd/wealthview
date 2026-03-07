package com.wealthview.importmodule.csv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FidelityPositionsCsvParserTest {

    private final FidelityPositionsCsvParser parser = new FidelityPositionsCsvParser();

    @Test
    void parse_validPositionsCsv_returnsOpeningBalanceTransactions() throws IOException {
        var csv = """
                Account Number,Account Name,Symbol,Description,Quantity,Last Price,Last Price Change,Current Value,Today's Gain/Loss Dollar,Today's Gain/Loss Percent,Total Gain/Loss Dollar,Total Gain/Loss Percent,Percent Of Account,Cost Basis Total,Average Cost Basis,Type
                X12345678,INDIVIDUAL,AMZN,AMAZON.COM INC,50,$185.50,+$1.25,"$9,275.00",+$62.50,+0.68%,+$1275.00,+15.93%,25.00%,"$8,000.00",$160.00,Cash
                X12345678,INDIVIDUAL,NVDA,NVIDIA CORP,100,$950.00,+$15.00,"$95,000.00","+$1,500.00",+1.60%,"+$45,000.00",+90.00%,50.00%,"$50,000.00",$500.00,Cash
                X12345678,INDIVIDUAL,SCHD,SCHWAB US DIVIDEND EQUITY ETF,200,$82.35,-$0.15,"$16,470.00",-$30.00,-0.18%,"+$2,470.00",+17.64%,15.00%,"$14,000.00",$70.00,Cash
                X12345678,INDIVIDUAL,VOO,VANGUARD S&P 500 ETF,20,$520.00,+$3.50,"$10,400.00",+$70.00,+0.68%,"+$1,400.00",+15.56%,10.00%,"$9,000.00",$450.00,Cash

                The data and calculations contained in this report are believed to be accurate.
                Date downloaded Mar-05-2026 10:30:15 ET
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(4);
        assertThat(result.errors()).isEmpty();

        var amzn = result.transactions().get(0);
        assertThat(amzn.symbol()).isEqualTo("AMZN");
        assertThat(amzn.type()).isEqualTo("opening_balance");
        assertThat(amzn.quantity()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(amzn.amount()).isEqualByComparingTo(new BigDecimal("8000.00"));
        assertThat(amzn.date()).isEqualTo(LocalDate.of(2026, 3, 5));

        var nvda = result.transactions().get(1);
        assertThat(nvda.symbol()).isEqualTo("NVDA");
        assertThat(nvda.quantity()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(nvda.amount()).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    void parse_skipsPendingActivityRows() throws IOException {
        var csv = """
                Account Number,Account Name,Symbol,Description,Quantity,Last Price,Last Price Change,Current Value,Today's Gain/Loss Dollar,Today's Gain/Loss Percent,Total Gain/Loss Dollar,Total Gain/Loss Percent,Percent Of Account,Cost Basis Total,Average Cost Basis,Type
                X12345678,INDIVIDUAL,AMZN,AMAZON.COM INC,50,$185.50,+$1.25,"$9,275.00",+$62.50,+0.68%,+$1275.00,+15.93%,25.00%,"$8,000.00",$160.00,Cash
                X12345678,INDIVIDUAL,Pending Activity,,,,,,,,,,,,,,
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).symbol()).isEqualTo("AMZN");
    }

    @Test
    void parse_fcashRow_createsDepositInsteadOfSkipping() throws IOException {
        var csv = """
                Account Number,Account Name,Symbol,Description,Quantity,Last Price,Last Price Change,Current Value,Today's Gain/Loss Dollar,Today's Gain/Loss Percent,Total Gain/Loss Dollar,Total Gain/Loss Percent,Percent Of Account,Cost Basis Total,Average Cost Basis,Type
                X12345678,INDIVIDUAL,AMZN,AMAZON.COM INC,50,$185.50,+$1.25,"$9,275.00",+$62.50,+0.68%,+$1275.00,+15.93%,25.00%,"$8,000.00",$160.00,Cash
                X12345678,INDIVIDUAL,FCASH**,HELD IN FCASH,1500.00,$1.00,$0.00,"$1,500.00",$0.00,0.00%,$0.00,0.00%,5.00%,"$1,500.00",$1.00,Cash
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(2);
        assertThat(result.transactions().get(0).symbol()).isEqualTo("AMZN");
        var fcash = result.transactions().get(1);
        assertThat(fcash.type()).isEqualTo("deposit");
        assertThat(fcash.symbol()).isNull();
        assertThat(fcash.amount()).isEqualByComparingTo(new BigDecimal("1500.00"));
    }

    @Test
    void parse_moneyMarketDashCostBasis_usesQuantityAsAmount() throws IOException {
        var csv = """
                Account Number,Account Name,Symbol,Description,Quantity,Last Price,Last Price Change,Current Value,Today's Gain/Loss Dollar,Today's Gain/Loss Percent,Total Gain/Loss Dollar,Total Gain/Loss Percent,Percent Of Account,Cost Basis Total,Average Cost Basis,Type
                X12345678,INDIVIDUAL,SPAXX,FIDELITY GOVERNMENT MONEY MARKET,"2,500.00",$1.00,$0.00,"$2,500.00",$0.00,0.00%,$0.00,0.00%,10.00%,--,$1.00,Cash
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        var spaxx = result.transactions().get(0);
        assertThat(spaxx.symbol()).isEqualTo("SPAXX");
        assertThat(spaxx.quantity()).isEqualByComparingTo(new BigDecimal("2500.00"));
        assertThat(spaxx.amount()).isEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    void parse_extractsDateFromFooter() throws IOException {
        var csv = """
                Account Number,Account Name,Symbol,Description,Quantity,Last Price,Last Price Change,Current Value,Today's Gain/Loss Dollar,Today's Gain/Loss Percent,Total Gain/Loss Dollar,Total Gain/Loss Percent,Percent Of Account,Cost Basis Total,Average Cost Basis,Type
                X12345678,INDIVIDUAL,AMZN,AMAZON.COM INC,50,$185.50,+$1.25,"$9,275.00",+$62.50,+0.68%,+$1275.00,+15.93%,25.00%,"$8,000.00",$160.00,Cash

                Date downloaded Jan-15-2026 09:00:00 ET
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void parse_noFooterDate_fallsBackToToday() throws IOException {
        var csv = """
                Account Number,Account Name,Symbol,Description,Quantity,Last Price,Last Price Change,Current Value,Today's Gain/Loss Dollar,Today's Gain/Loss Percent,Total Gain/Loss Dollar,Total Gain/Loss Percent,Percent Of Account,Cost Basis Total,Average Cost Basis,Type
                X12345678,INDIVIDUAL,AMZN,AMAZON.COM INC,50,$185.50,+$1.25,"$9,275.00",+$62.50,+0.68%,+$1275.00,+15.93%,25.00%,"$8,000.00",$160.00,Cash
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.now());
    }

    @Test
    void parse_realPortfolioFile_parsesAllPositions() throws IOException {
        var is = getClass().getResourceAsStream("/testdata/Portfolio_Positions_Mar-05-2026.csv");
        assertThat(is).isNotNull();

        var result = parser.parse(new InputStreamReader(is, StandardCharsets.UTF_8));

        assertThat(result.errors()).isEmpty();
        // 6 transactions: FCASH as deposit + AMZN, NVDA, SCHD, SPAXX, VOO as opening_balance
        assertThat(result.transactions()).hasSize(6);

        var fcash = result.transactions().stream()
                .filter(t -> t.type().equals("deposit")).findFirst().orElseThrow();
        assertThat(fcash.symbol()).isNull();
        assertThat(fcash.quantity()).isNull();
        assertThat(fcash.amount()).isEqualByComparingTo(new BigDecimal("704.82"));
        assertThat(fcash.date()).isEqualTo(LocalDate.of(2026, 3, 5));

        var amzn = result.transactions().stream()
                .filter(t -> "AMZN".equals(t.symbol())).findFirst().orElseThrow();
        assertThat(amzn.type()).isEqualTo("opening_balance");
        assertThat(amzn.quantity()).isEqualByComparingTo(new BigDecimal("1100"));
        assertThat(amzn.amount()).isEqualByComparingTo(new BigDecimal("112324.74"));

        var spaxx = result.transactions().stream()
                .filter(t -> "SPAXX".equals(t.symbol())).findFirst().orElseThrow();
        assertThat(spaxx.type()).isEqualTo("opening_balance");
        assertThat(spaxx.quantity()).isEqualByComparingTo(new BigDecimal("196049.86"));
        assertThat(spaxx.amount()).isEqualByComparingTo(new BigDecimal("196049.86"));

        var voo = result.transactions().stream()
                .filter(t -> "VOO".equals(t.symbol())).findFirst().orElseThrow();
        assertThat(voo.quantity()).isEqualByComparingTo(new BigDecimal("483"));
        assertThat(voo.amount()).isEqualByComparingTo(new BigDecimal("303824.32"));
    }

    @Test
    void parse_fcashRow_createsDepositTransaction() throws IOException {
        var csv = """
                Account Number,Account Name,Symbol,Description,Quantity,Last Price,Last Price Change,Current Value,Today's Gain/Loss Dollar,Today's Gain/Loss Percent,Total Gain/Loss Dollar,Total Gain/Loss Percent,Percent Of Account,Cost Basis Total,Average Cost Basis,Type
                X12345678,INDIVIDUAL,FCASH**,HELD IN FCASH,,,,$1500.00,,,,,5.00%,,,Cash
                X12345678,INDIVIDUAL,AMZN,AMAZON.COM INC,50,$185.50,+$1.25,"$9,275.00",+$62.50,+0.68%,+$1275.00,+15.93%,25.00%,"$8,000.00",$160.00,Cash
                """;

        var result = parser.parse(new StringReader(csv));

        assertThat(result.transactions()).hasSize(2);
        var deposit = result.transactions().get(0);
        assertThat(deposit.type()).isEqualTo("deposit");
        assertThat(deposit.symbol()).isNull();
        assertThat(deposit.quantity()).isNull();
        assertThat(deposit.amount()).isEqualByComparingTo(new BigDecimal("1500.00"));
    }

    @Test
    void parse_emptyFile_returnsEmptyResult() throws IOException {
        var result = parser.parse(new StringReader(""));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }
}
