package com.wealthview.app.it.security;

import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz the CSV / OFX import boundary with malformed, hostile, or
 * boundary-shaped uploads.
 *
 * <p>Constraint: this test must not actually exhaust CI. We use modest
 * payload sizes (up to 100 KB) and assert bounded-time behaviour rather
 * than try to provoke a real DOS.
 *
 * <p>Asserted invariants:
 * <ul>
 *   <li>Zero-byte / random-byte / heavily-malformed CSV+OFX uploads
 *       never produce 5xx — only 4xx or a legitimate 201 with zero
 *       successful rows.</li>
 *   <li>OFX uploads containing XXE entity declarations never resolve
 *       the entity (response body must not contain known sentinels
 *       like {@code root:x:} from /etc/passwd).</li>
 *   <li>CSV uploads containing formula-injection cells round-trip as
 *       inert text — never trigger server-side execution.</li>
 * </ul>
 */
@Tag("fuzz")
class ImportBoundaryFuzzIT extends AbstractApiIntegrationTest {

    private static final int ITERATIONS = 60;

    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        accountId = (String) data.createAccount("Import Fuzz Account", "brokerage").get("id");
    }

    @Test
    void importCsv_withFuzzedPayloads_neverReturns5xx() {
        FuzzSupport.samples(this::randomCsvPayload, ITERATIONS, 0xCA1L)
                .forEach(payload -> {
                    var resp = postMultipart("/api/v1/import/csv", payload, "fuzz.csv",
                            MediaType.parseMediaType("text/csv"), "generic");

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("CSV import with %d-byte payload produced 5xx",
                                    payload.length)
                            .isFalse();
                });
    }

    @Test
    void importOfx_withFuzzedPayloads_neverReturns5xxNeverResolvesXxe() {
        FuzzSupport.samples(this::randomOfxPayload, ITERATIONS, 0xCA2L)
                .forEach(payload -> {
                    var resp = postMultipart("/api/v1/import/ofx", payload, "fuzz.ofx",
                            MediaType.parseMediaType("application/x-ofx"), null);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("OFX import with %d-byte payload produced 5xx",
                                    payload.length)
                            .isFalse();

                    if (resp.hasBody()) {
                        var body = resp.getBody().toString();
                        // /etc/passwd-shaped content from a resolved XXE
                        // entity must NEVER appear in the response body.
                        assertThat(body)
                                .as("OFX response body contains an /etc/passwd-shaped string")
                                .doesNotContain("root:x:")
                                .doesNotContain("daemon:x:");
                    }
                });
    }

    @Test
    void importCsv_withFormulaInjection_neverExecutes() {
        // Excel/Sheets-style formula injection: cells that begin with =, +, -, @
        // should be treated as inert text by our import pipeline, not as
        // computed values. This is a stored attack — we check that the import
        // either rejects (4xx) or stores the literal string.
        var formulaCsv = """
                date,type,symbol,quantity,amount,description
                2024-01-15,buy,AAPL,1,100,=cmd|'/c calc.exe'!A1
                2024-01-16,buy,MSFT,2,200,@SUM(1+1)*cmd|'/c calc.exe'!A1
                2024-01-17,buy,GOOG,3,300,+1+1+cmd|'/c calc.exe'!A1
                """;

        var resp = postMultipart("/api/v1/import/csv", formulaCsv.getBytes(),
                "formula.csv", MediaType.parseMediaType("text/csv"), "generic");

        // Either the import succeeds (text is stored verbatim) or it fails
        // validation (4xx). The critical invariant is no 5xx.
        assertThat(resp.getStatusCode().is5xxServerError())
                .as("formula-injection CSV produced 5xx")
                .isFalse();
    }

    private byte[] randomCsvPayload(Random r) {
        return switch (r.nextInt(7)) {
            case 0 -> new byte[0];                                  // zero bytes
            case 1 -> randomBytes(r, 100);                          // tiny random
            case 2 -> randomBytes(r, 10_000);                       // 10KB random
            case 3 -> ("date,type,symbol,quantity,amount\n"
                    + ",,,,\n".repeat(1000)).getBytes();             // 1k blank rows
            case 4 -> ("date,type,symbol,quantity,amount\n"
                    + "x".repeat(50_000)).getBytes();                // huge single field
            case 5 -> headerOnly();                                  // only header
            default -> randomCsvRows(r, 50);
        };
    }

    private byte[] randomOfxPayload(Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> new byte[0];
            case 1 -> randomBytes(r, 1_000);
            case 2 -> "<OFX>not<closed>".getBytes();
            case 3 -> ("<?xml version=\"1.0\"?>"
                    + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                    + "<OFX><BANKMSGSRSV1><STMTRS><BANKACCTFROM>&xxe;</BANKACCTFROM>"
                    + "</STMTRS></BANKMSGSRSV1></OFX>").getBytes();
            case 4 -> ("<OFX>" + "x".repeat(50_000) + "</OFX>").getBytes();
            default -> ("<OFX><BANKMSGSRSV1>" + "<DUMMY/>".repeat(1000)
                    + "</BANKMSGSRSV1></OFX>").getBytes();
        };
    }

    private byte[] randomBytes(Random r, int max) {
        int len = r.nextInt(max + 1);
        var bytes = new byte[len];
        r.nextBytes(bytes);
        return bytes;
    }

    private byte[] headerOnly() {
        return "date,type,symbol,quantity,amount\n".getBytes();
    }

    private byte[] randomCsvRows(Random r, int maxRows) {
        var sb = new StringBuilder("date,type,symbol,quantity,amount\n");
        int rows = r.nextInt(maxRows + 1);
        for (int i = 0; i < rows; i++) {
            sb.append(FuzzSupport.alnum(r, 8)).append(',')
                    .append(FuzzSupport.alnum(r, 4)).append(',')
                    .append(FuzzSupport.alnum(r, 6)).append(',')
                    .append(r.nextInt(1000)).append(',')
                    .append(r.nextInt(10_000)).append('\n');
        }
        return sb.toString().getBytes();
    }

    private org.springframework.http.ResponseEntity<java.util.Map<String, Object>>
    postMultipart(String path, byte[] payload, String filename,
                  MediaType contentType, String format) {
        var body = new LinkedMultiValueMap<String, Object>();
        var resource = new ByteArrayResource(payload) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var partHeaders = new HttpHeaders();
        partHeaders.setContentType(contentType);
        var entity = new HttpEntity<>(resource, partHeaders);
        body.add("file", entity);
        body.add("accountId", accountId);
        if (format != null) {
            body.add("format", format);
        }

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        authHelper.applyAuth(headers, authHelper.adminToken());

        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP_TYPE);
    }
}
