package com.wealthview.importmodule.zillow;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Exercises the three public HTTP methods on ZillowScraperClient that go through
 * Jsoup.connect() to a real network. These are the methods that were previously
 * uncovered — the existing ZillowScraperClientTest covers only the pure-HTML
 * parsing helpers (extractZestimate, extractSearchResults).
 *
 * Uses Mockito.mockStatic on Jsoup so no real network traffic is generated;
 * each test stubs the Connection.get() call to return a pre-parsed Document
 * (or to throw IOException on the error-path tests).
 *
 * The stubbed Connection is built BEFORE the mockStatic scope opens; stubbing
 * mock-instance methods while a static mock is active tangles Mockito's state
 * machine and yields UnfinishedStubbingException.
 */
class ZillowScraperClientHttpTest {

    private final ZillowScraperClient client = new ZillowScraperClient(5000);

    private static Connection stubConnection(Document doc) throws IOException {
        var connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenReturn(doc);
        return connection;
    }

    private static Connection throwingConnection() throws IOException {
        var connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenThrow(new IOException("network down"));
        return connection;
    }

    @Test
    void getValuation_withZestimateInPage_returnsValue() throws Exception {
        var doc = Jsoup.parse("""
                <html><body><script type="application/json">
                {"property":{"zpid":"1","zestimate":525000}}
                </script></body></html>
                """);
        var connection = stubConnection(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            var result = client.getValuation("123 Main St");

            assertThat(result).isPresent();
            assertThat(result.get().value()).isEqualByComparingTo("525000");
        }
    }

    @Test
    void getValuation_onIoException_returnsEmpty() throws Exception {
        var connection = throwingConnection();

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            var result = client.getValuation("123 Main St");

            assertThat(result).isEmpty();
        }
    }

    @Test
    void getValuation_passesFormattedAddressIntoUrl() throws Exception {
        var connection = stubConnection(Jsoup.parse("<html></html>"));

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            client.getValuation("123 Main St, Seattle, WA");

            jsoup.verify(() -> Jsoup.connect(
                    org.mockito.ArgumentMatchers.argThat((String url) ->
                            url != null && url.contains("123-Main-St-Seattle-WA"))));
        }
    }

    @Test
    void getValuationByZpid_withZestimate_returnsValue() throws Exception {
        var doc = Jsoup.parse("""
                <html><body><script type="application/json">
                {"zpid":"99999","zestimate":675000}
                </script></body></html>
                """);
        var connection = stubConnection(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            var result = client.getValuationByZpid("99999");

            assertThat(result).isPresent();
            assertThat(result.get().value()).isEqualByComparingTo("675000");
        }
    }

    @Test
    void getValuationByZpid_onIoException_returnsEmpty() throws Exception {
        var connection = throwingConnection();

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            var result = client.getValuationByZpid("99999");

            assertThat(result).isEmpty();
        }
    }

    @Test
    void getValuationByZpid_passesZpidIntoUrl() throws Exception {
        var connection = stubConnection(Jsoup.parse("<html></html>"));

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            client.getValuationByZpid("777888");

            jsoup.verify(() -> Jsoup.connect(
                    org.mockito.ArgumentMatchers.argThat((String url) ->
                            url != null && url.contains("777888_zpid"))));
        }
    }

    @Test
    void searchProperties_withResults_returnsList() throws Exception {
        var doc = Jsoup.parse("""
                <html><body><script type="application/json">
                {"results":[
                  {"zpid":"123","address":"100 Oak","zestimate":400000},
                  {"zpid":"456","address":"200 Elm","zestimate":550000}
                ]}
                </script></body></html>
                """);
        var connection = stubConnection(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            var results = client.searchProperties("Oak St");

            assertThat(results).hasSize(2);
            assertThat(results.get(0).zpid()).isEqualTo("123");
        }
    }

    @Test
    void searchProperties_onIoException_returnsEmptyList() throws Exception {
        var connection = throwingConnection();

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            var results = client.searchProperties("nowhere");

            assertThat(results).isEmpty();
        }
    }

    @Test
    void searchProperties_withPropertyCardsOnly_fallsBackToDomExtraction() throws Exception {
        var doc = Jsoup.parse("""
                <html><body>
                <div data-test="property-card">
                  <a data-test="property-card-link" href="/homes/12345_zpid">100 Oak St</a>
                  <div data-test="property-card-price">$400,000</div>
                </div>
                </body></html>
                """);
        var connection = stubConnection(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);

            var results = client.searchProperties("Oak");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).zpid()).isEqualTo("12345");
            assertThat(results.get(0).zestimate()).isEqualByComparingTo("400000");
        }
    }

    @Test
    void extractSearchResults_altPatternWithIndividualPropertyJson_parsesFullAddress() {
        var doc = Jsoup.parse("""
                <html><body><script type="application/json">
                {"zpid":"555","streetAddress":"100 Oak","city":"Seattle",
                "state":"WA","zipcode":"98101","zestimate":400000}
                </script></body></html>
                """);

        var results = client.extractSearchResults(doc);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).address()).isEqualTo("100 Oak, Seattle, WA 98101");
    }

    @Test
    void extractSearchResults_individualJsonWithoutAddress_fallsBackToPageTitle() {
        var doc = Jsoup.parse("""
                <html><head><title>Fancy Property | Zillow</title></head><body>
                <script type="application/json">
                {"zpid":"777","zestimate":500000}
                </script></body></html>
                """);

        var results = client.extractSearchResults(doc);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).zpid()).isEqualTo("777");
        assertThat(results.get(0).address()).isEqualTo("Fancy Property");
    }
}
