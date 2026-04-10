package com.wealthview.importmodule.zillow;

import com.wealthview.core.property.PropertyValuationClient;
import com.wealthview.core.property.dto.PropertyValuationResult;
import com.wealthview.core.property.dto.ZillowSearchResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class ZillowScraperClient implements PropertyValuationClient {

    private static final Logger log = LoggerFactory.getLogger(ZillowScraperClient.class);
    private static final String ZILLOW_BASE_URL = "https://www.zillow.com/homes/";
    private static final String ZILLOW_PROPERTY_URL = "https://www.zillow.com/homedetails/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Pattern ZESTIMATE_PATTERN = Pattern.compile(
            "\"zestimate\"\\s*:\\s*(\\d+)");
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "\\$([\\d,]+)");
    private static final Pattern ZPID_PATTERN = Pattern.compile(
            "\"zpid\"\\s*:\\s*\"?(\\d+)\"?");
    private static final Pattern SEARCH_RESULT_PATTERN = Pattern.compile(
            "\"zpid\"\\s*:\\s*\"?(\\d+)\"?[^}]*?" +
            "\"address\"\\s*:\\s*\"([^\"]+)\"[^}]*?" +
            "\"zestimate\"\\s*:\\s*(\\d+)");
    private static final Pattern SEARCH_RESULT_ALT_PATTERN = Pattern.compile(
            "\"zpid\"\\s*:\\s*\"?(\\d+)\"?[^}]*?" +
            "\"streetAddress\"\\s*:\\s*\"([^\"]+)\"[^}]*?" +
            "\"city\"\\s*:\\s*\"([^\"]+)\"[^}]*?" +
            "\"state\"\\s*:\\s*\"([^\"]+)\"[^}]*?" +
            "\"zipcode\"\\s*:\\s*\"([^\"]+)\"");

    private final int timeoutMs;

    public ZillowScraperClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Optional<PropertyValuationResult> getValuation(String address) {
        try {
            var url = ZILLOW_BASE_URL + formatAddressForUrl(address) + "_rb/";
            log.debug("Fetching Zillow page: {}", url);

            var doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .get();

            return extractZestimate(doc);
        } catch (IOException e) {
            log.warn("Failed to fetch Zillow valuation for address '{}'", address, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<PropertyValuationResult> getValuationByZpid(String zpid) {
        try {
            var url = ZILLOW_PROPERTY_URL + zpid + "_zpid/";
            log.debug("Fetching Zillow property by zpid: {}", url);

            var doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .get();

            return extractZestimate(doc);
        } catch (IOException e) {
            log.warn("Failed to fetch Zillow valuation for zpid '{}'", zpid, e);
            return Optional.empty();
        }
    }

    @Override
    public List<ZillowSearchResult> searchProperties(String address) {
        try {
            var url = ZILLOW_BASE_URL + formatAddressForUrl(address) + "_rb/";
            log.debug("Searching Zillow for address: {}", url);

            var doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .get();

            return extractSearchResults(doc);
        } catch (IOException e) {
            log.warn("Failed to search Zillow for address '{}'", address, e);
            return Collections.emptyList();
        }
    }

    Optional<PropertyValuationResult> extractZestimate(Document doc) {
        for (var script : doc.select("script[type=application/json]")) {
            var data = script.data();
            var matcher = ZESTIMATE_PATTERN.matcher(data);
            if (matcher.find()) {
                var value = new BigDecimal(matcher.group(1));
                log.info("Extracted Zestimate from JSON: {}", value);
                return Optional.of(new PropertyValuationResult(value, LocalDate.now()));
            }
        }

        var zestimateElement = doc.selectFirst("[data-testid=zestimate-text]");
        if (zestimateElement != null) {
            var text = zestimateElement.text();
            var matcher = PRICE_PATTERN.matcher(text);
            if (matcher.find()) {
                var value = new BigDecimal(matcher.group(1).replace(",", ""));
                log.info("Extracted Zestimate from DOM: {}", value);
                return Optional.of(new PropertyValuationResult(value, LocalDate.now()));
            }
        }

        log.warn("No Zestimate found in page");
        return Optional.empty();
    }

    List<ZillowSearchResult> extractSearchResults(Document doc) {
        var results = new ArrayList<ZillowSearchResult>();

        for (var script : doc.select("script[type=application/json]")) {
            var data = script.data();

            // Try structured search result pattern with full address
            var matcher = SEARCH_RESULT_PATTERN.matcher(data);
            while (matcher.find()) {
                var zpid = matcher.group(1);
                var resultAddress = matcher.group(2);
                var zestimate = new BigDecimal(matcher.group(3));
                if (!containsZpid(results, zpid)) {
                    results.add(new ZillowSearchResult(zpid, resultAddress, zestimate));
                }
            }

            // If no results with full address, try individual property data
            if (results.isEmpty()) {
                var zpidMatcher = ZPID_PATTERN.matcher(data);
                var zestMatcher = ZESTIMATE_PATTERN.matcher(data);
                if (zpidMatcher.find() && zestMatcher.find()) {
                    var zpid = zpidMatcher.group(1);
                    var zestimate = new BigDecimal(zestMatcher.group(1));

                    // Try to extract address components
                    var addrMatcher = SEARCH_RESULT_ALT_PATTERN.matcher(data);
                    String fullAddress;
                    if (addrMatcher.find()) {
                        fullAddress = addrMatcher.group(2) + ", " + addrMatcher.group(3) + ", "
                                + addrMatcher.group(4) + " " + addrMatcher.group(5);
                    } else {
                        fullAddress = doc.title().replaceAll("\\s*[|-].*Zillow.*", "").trim();
                    }

                    if (!containsZpid(results, zpid)) {
                        results.add(new ZillowSearchResult(zpid, fullAddress, zestimate));
                    }
                }
            }
        }

        // Fallback: try search result cards in the DOM
        if (results.isEmpty()) {
            for (var card : doc.select("[data-test=property-card]")) {
                var link = card.selectFirst("a[data-test=property-card-link]");
                var priceEl = card.selectFirst("[data-test=property-card-price]");

                if (link != null && priceEl != null) {
                    var href = link.attr("href");
                    var zpidMatch = Pattern.compile("/(\\d+)_zpid").matcher(href);
                    var priceMatch = PRICE_PATTERN.matcher(priceEl.text());

                    if (zpidMatch.find() && priceMatch.find()) {
                        var zpid = zpidMatch.group(1);
                        var zestimate = new BigDecimal(priceMatch.group(1).replace(",", ""));
                        var cardAddress = link.text().trim();
                        if (!containsZpid(results, zpid)) {
                            results.add(new ZillowSearchResult(zpid, cardAddress, zestimate));
                        }
                    }
                }
            }
        }

        log.info("Found {} search results from Zillow", results.size());
        return results;
    }

    private boolean containsZpid(List<ZillowSearchResult> results, String zpid) {
        return results.stream().anyMatch(r -> r.zpid().equals(zpid));
    }

    String formatAddressForUrl(String address) {
        var sanitized = address.replaceAll("[^A-Za-z0-9 \\-]", "");
        return sanitized.trim().replaceAll("\\s+", "-");
    }
}
