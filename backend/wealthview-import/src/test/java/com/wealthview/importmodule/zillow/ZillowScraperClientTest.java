package com.wealthview.importmodule.zillow;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ZillowScraperClientTest {

    private final ZillowScraperClient client = new ZillowScraperClient(10000);

    @Test
    void extractZestimate_validJsonLd_extractsValue() throws IOException {
        var html = loadFixture("zillow/valid-zestimate.html");
        var doc = Jsoup.parse(html);

        var result = client.extractZestimate(doc);

        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualByComparingTo("450000");
    }

    @Test
    void extractZestimate_domOnly_extractsFromElement() throws IOException {
        var html = loadFixture("zillow/dom-only-zestimate.html");
        var doc = Jsoup.parse(html);

        var result = client.extractZestimate(doc);

        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualByComparingTo("325000");
    }

    @Test
    void extractZestimate_missingData_returnsEmpty() throws IOException {
        var html = loadFixture("zillow/missing-zestimate.html");
        var doc = Jsoup.parse(html);

        var result = client.extractZestimate(doc);

        assertThat(result).isEmpty();
    }

    @Test
    void extractZestimate_malformedHtml_returnsEmpty() throws IOException {
        var html = loadFixture("zillow/malformed.html");
        var doc = Jsoup.parse(html);

        var result = client.extractZestimate(doc);

        assertThat(result).isEmpty();
    }

    @Test
    void extractSearchResults_singlePropertyPage_returnsSingleResult() {
        var html = """
                <html><head><title>123 Main St - Zillow</title></head><body>
                <script type="application/json">
                {"property":{"zpid":"12345","streetAddress":"123 Main St","city":"Springfield",
                "state":"IL","zipcode":"62701","zestimate":450000}}
                </script></body></html>
                """;
        var doc = Jsoup.parse(html);

        var results = client.extractSearchResults(doc);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).zpid()).isEqualTo("12345");
        assertThat(results.get(0).address()).isEqualTo("123 Main St, Springfield, IL 62701");
        assertThat(results.get(0).zestimate()).isEqualByComparingTo("450000");
    }

    @Test
    void extractSearchResults_multipleResults_returnsAll() {
        var html = """
                <html><head><title>Search Results</title></head><body>
                <script type="application/json">
                {"results":[
                  {"zpid":"111","address":"123 Main St Unit A","zestimate":350000},
                  {"zpid":"222","address":"123 Main St Unit B","zestimate":375000}
                ]}
                </script></body></html>
                """;
        var doc = Jsoup.parse(html);

        var results = client.extractSearchResults(doc);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).zpid()).isEqualTo("111");
        assertThat(results.get(1).zpid()).isEqualTo("222");
    }

    @Test
    void extractSearchResults_duplicateZpids_deduplicates() {
        var html = """
                <html><head><title>Test</title></head><body>
                <script type="application/json">
                {"results":[
                  {"zpid":"111","address":"123 Main St","zestimate":350000},
                  {"zpid":"111","address":"123 Main St","zestimate":350000}
                ]}
                </script></body></html>
                """;
        var doc = Jsoup.parse(html);

        var results = client.extractSearchResults(doc);

        assertThat(results).hasSize(1);
    }

    @Test
    void extractSearchResults_noData_returnsEmptyList() {
        var html = """
                <html><head><title>No Results</title></head><body>
                <p>No properties found</p>
                </body></html>
                """;
        var doc = Jsoup.parse(html);

        var results = client.extractSearchResults(doc);

        assertThat(results).isEmpty();
    }

    @Test
    void formatAddressForUrl_basicAddress_replacesSpacesWithDashes() {
        assertThat(client.formatAddressForUrl("123 Main St, Seattle, WA"))
                .isEqualTo("123-Main-St-Seattle-WA");
    }

    @Test
    void formatAddressForUrl_stripsPathTraversalAndQueryChars() {
        // A crafted address must not escape the URL path or inject a query/fragment.
        assertThat(client.formatAddressForUrl("../admin?x=1#frag"))
                .doesNotContain("..")
                .doesNotContain("/")
                .doesNotContain("?")
                .doesNotContain("#");
    }

    @Test
    void formatAddressForUrl_stripsUrlMetaChars() {
        var result = client.formatAddressForUrl("123 Main/St&evil=yes");
        assertThat(result).doesNotContain("/");
        assertThat(result).doesNotContain("&");
        assertThat(result).doesNotContain("=");
    }

    private String loadFixture(String path) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Fixture not found: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
