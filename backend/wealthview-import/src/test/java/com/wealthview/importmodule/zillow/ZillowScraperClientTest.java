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

    private String loadFixture(String path) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Fixture not found: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
