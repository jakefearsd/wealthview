package com.wealthview.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZillowConfigTest {

    @Test
    void zillowScraperClient_returnsNonNullInstanceWithProvidedTimeout() {
        var config = new ZillowConfig();

        var client = config.zillowScraperClient(15000);

        assertThat(client).isNotNull();
    }
}
