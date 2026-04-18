package com.wealthview.app.config;

import com.wealthview.core.pricefeed.PriceSyncService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PriceSyncSchedulerTest {

    @Test
    void scheduleDailyPriceSync_invokesSyncDailyPricesOnTheService() {
        var priceSyncService = mock(PriceSyncService.class);
        var scheduler = new PriceSyncScheduler(priceSyncService);

        scheduler.scheduleDailyPriceSync();

        verify(priceSyncService).syncDailyPrices();
    }
}
