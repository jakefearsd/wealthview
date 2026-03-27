package com.wealthview.app.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsConfigTest {

    @Test
    void timedAspect_createsAspectWithRegistry() {
        var config = new MetricsConfig();
        var registry = new SimpleMeterRegistry();

        var aspect = config.timedAspect(registry);

        assertThat(aspect).isNotNull().isInstanceOf(TimedAspect.class);
    }
}
