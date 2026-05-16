package com.wealthview.api.testutil;

import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.wealthview.api.common.ClientIpResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@TestConfiguration
public class TestMetricsConfig {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public ClientIpResolver clientIpResolver() {
        return new ClientIpResolver(List.of());
    }
}
