package com.wealthview.app.config;

import com.wealthview.core.pricefeed.PriceSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(PriceSyncService.class)
public class PriceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceSyncScheduler.class);

    private final PriceSyncService priceSyncService;

    public PriceSyncScheduler(PriceSyncService priceSyncService) {
        this.priceSyncService = priceSyncService;
    }

    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "America/New_York")
    public void scheduleDailyPriceSync() {
        log.info("Scheduled daily price sync triggered");
        priceSyncService.syncDailyPrices();
    }
}
