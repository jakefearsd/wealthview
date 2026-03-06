package com.wealthview.importmodule.zillow;

import com.wealthview.core.property.PropertyValuationClient;
import com.wealthview.core.property.dto.PropertyValuationResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

public class ZillowScraperClient implements PropertyValuationClient {

    private static final Logger log = LoggerFactory.getLogger(ZillowScraperClient.class);
    private static final String ZILLOW_BASE_URL = "https://www.zillow.com/homes/";
    private static final Pattern ZESTIMATE_PATTERN = Pattern.compile(
            "\"zestimate\"\\s*:\\s*(\\d+)");
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "\\$([\\d,]+)");

    private final int timeoutMs;

    public ZillowScraperClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Optional<PropertyValuationResult> getValuation(String address) {
        try {
            var url = ZILLOW_BASE_URL + address.replace(" ", "-").replace(",", "") + "_rb/";
            log.debug("Fetching Zillow page: {}", url);

            var doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                    .timeout(timeoutMs)
                    .get();

            return extractZestimate(doc);
        } catch (Exception e) {
            log.warn("Failed to fetch Zillow valuation for address '{}': {}", address, e.getMessage());
            return Optional.empty();
        }
    }

    Optional<PropertyValuationResult> extractZestimate(Document doc) {
        // Try JSON-LD script tags first
        for (var script : doc.select("script[type=application/json]")) {
            var data = script.data();
            var matcher = ZESTIMATE_PATTERN.matcher(data);
            if (matcher.find()) {
                var value = new BigDecimal(matcher.group(1));
                log.info("Extracted Zestimate from JSON: {}", value);
                return Optional.of(new PropertyValuationResult(value, LocalDate.now()));
            }
        }

        // Fallback: look for Zestimate in DOM elements
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
}
