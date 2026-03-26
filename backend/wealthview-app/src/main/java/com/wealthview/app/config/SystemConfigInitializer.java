package com.wealthview.app.config;

import com.wealthview.core.config.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * Seeds system configuration defaults into the database on startup.
 * Values are read from application.yml (or environment variables) and stored
 * in the system_config table if the key does not already exist. Admin users
 * can view and update these values via the super-admin UI. API key changes
 * take effect after the next application restart.
 */
@Component
@Order(2)
public class SystemConfigInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigInitializer.class);

    private final SystemConfigService configService;

    @Value("${app.finnhub.api-key:}")
    private String finnhubApiKey;

    @Value("${app.finnhub.rate-limit-ms:1100}")
    private String finnhubRateLimit;

    @Value("${app.yahoo.rate-limit-ms:500}")
    private String yahooRateLimit;

    public SystemConfigInitializer(SystemConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void run(String... args) {
        var defaults = new LinkedHashMap<String, String>();
        if (finnhubApiKey != null && !finnhubApiKey.isBlank()) {
            defaults.put("finnhub.api-key", finnhubApiKey);
        }
        defaults.put("finnhub.rate-limit-ms", finnhubRateLimit);
        defaults.put("yahoo.rate-limit-ms", yahooRateLimit);
        configService.seedDefaults(defaults);
        log.info("System config defaults seeded ({} entries)", defaults.size());
    }

    // Package-private setters for unit testing without Spring context
    void setFinnhubApiKey(String finnhubApiKey) {
        this.finnhubApiKey = finnhubApiKey;
    }

    void setFinnhubRateLimit(String finnhubRateLimit) {
        this.finnhubRateLimit = finnhubRateLimit;
    }

    void setYahooRateLimit(String yahooRateLimit) {
        this.yahooRateLimit = yahooRateLimit;
    }
}
