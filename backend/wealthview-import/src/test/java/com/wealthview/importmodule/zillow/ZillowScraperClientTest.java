package com.wealthview.importmodule.zillow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

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

    // === Fallback tiers ===
    //
    // extractSearchResults degrades through three tiers: a structured JSON match with a full
    // address, then a looser zpid+zestimate match that reconstructs (or guesses) the address,
    // then a scrape of the rendered property cards. The tests above only ever reach the first
    // tier. Zillow's markup changes often, which is precisely why the later tiers exist — an
    // untested fallback is one that quietly stops working the day the first tier breaks.

    @Test
    void extractSearchResults_zpidAndZestimateButNoAddressFields_derivesAddressFromPageTitle() {
        // Tier 2 with no streetAddress/city/state/zip to rebuild from: the page title is the only
        // address left, minus Zillow's own branding suffix.
        var html = """
                <html><head><title>456 Oak Ave, Portland, OR 97201 | Zillow</title></head><body>
                <script type="application/json">
                {"zpid":"98765","zestimate":525000}
                </script></body></html>
                """;
        var doc = Jsoup.parse(html);

        var results = client.extractSearchResults(doc);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.zpid()).isEqualTo("98765");
            assertThat(result.zestimate()).isEqualByComparingTo("525000");
            assertThat(result.address())
                    .as("the Zillow branding suffix must be stripped from the title")
                    .isEqualTo("456 Oak Ave, Portland, OR 97201");
        });
    }

    @Test
    void extractSearchResults_noJsonAtAll_fallsBackToRenderedPropertyCards() {
        // Tier 3: the JSON blobs are gone entirely (the shape Zillow serves when it renders
        // server-side), leaving only the card markup.
        var html = """
                <html><head><title>Search</title></head><body>
                <div data-test="property-card">
                  <a data-test="property-card-link" href="/homedetails/789-Pine-St/55555_zpid/">789 Pine St</a>
                  <span data-test="property-card-price">$610,000</span>
                </div>
                </body></html>
                """;
        var doc = Jsoup.parse(html);

        var results = client.extractSearchResults(doc);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.zpid()).isEqualTo("55555");
            assertThat(result.address()).isEqualTo("789 Pine St");
            assertThat(result.zestimate())
                    .as("the card price carries a $ and thousands separators")
                    .isEqualByComparingTo("610000");
        });
    }

    @Test
    void extractSearchResults_propertyCardMissingItsPrice_isSkippedWithoutFailingTheRest() {
        var html = """
                <html><head><title>Search</title></head><body>
                <div data-test="property-card">
                  <a data-test="property-card-link" href="/homedetails/1-No-Price/11111_zpid/">1 No Price</a>
                </div>
                <div data-test="property-card">
                  <a data-test="property-card-link" href="/homedetails/2-Priced/22222_zpid/">2 Priced</a>
                  <span data-test="property-card-price">$700,000</span>
                </div>
                </body></html>
                """;
        var doc = Jsoup.parse(html);

        var results = client.extractSearchResults(doc);

        assertThat(results).singleElement()
                .satisfies(result -> assertThat(result.zpid()).isEqualTo("22222"));
    }

    @Test
    void extractSearchResults_propertyCardHrefWithoutAZpid_isSkipped() {
        var html = """
                <html><head><title>Search</title></head><body>
                <div data-test="property-card">
                  <a data-test="property-card-link" href="/b/some-building/">Building with no zpid</a>
                  <span data-test="property-card-price">$800,000</span>
                </div>
                </body></html>
                """;
        var doc = Jsoup.parse(html);

        assertThat(client.extractSearchResults(doc)).isEmpty();
    }

    @Test
    void extractSearchResults_duplicatePropertyCards_deduplicateByZpid() {
        var html = """
                <html><head><title>Search</title></head><body>
                <div data-test="property-card">
                  <a data-test="property-card-link" href="/homedetails/3-Repeat/33333_zpid/">3 Repeat</a>
                  <span data-test="property-card-price">$450,000</span>
                </div>
                <div data-test="property-card">
                  <a data-test="property-card-link" href="/homedetails/3-Repeat/33333_zpid/">3 Repeat</a>
                  <span data-test="property-card-price">$450,000</span>
                </div>
                </body></html>
                """;
        var doc = Jsoup.parse(html);

        assertThat(client.extractSearchResults(doc)).hasSize(1);
    }

    @Test
    void extractSearchResults_structuredJsonPresent_neverFallsThroughToTheCards() {
        // Guards the tier ordering: when tier 1 matches, the card scrape must not also run and
        // append a duplicate-but-differently-addressed entry.
        var html = """
                <html><head><title>Search</title></head><body>
                <script type="application/json">
                {"results":[{"zpid":"44444","address":"4 Structured St","zestimate":500000}]}
                </script>
                <div data-test="property-card">
                  <a data-test="property-card-link" href="/homedetails/4-Card/44444_zpid/">4 Card Address</a>
                  <span data-test="property-card-price">$999,000</span>
                </div>
                </body></html>
                """;
        var doc = Jsoup.parse(html);

        assertThat(client.extractSearchResults(doc)).singleElement().satisfies(result -> {
            assertThat(result.address()).isEqualTo("4 Structured St");
            assertThat(result.zestimate()).isEqualByComparingTo("500000");
        });
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
