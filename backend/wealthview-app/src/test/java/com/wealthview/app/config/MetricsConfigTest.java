package com.wealthview.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the TimedAspect built by MetricsConfig is functional: when applied to a
 * proxy around a {@code @Timed}-annotated method, invoking that method records a
 * timer in the exact registry the bean factory was given.
 */
class MetricsConfigTest {

    @Test
    void timedAspect_timedAnnotatedMethodCall_recordsTimerInGivenRegistry() {
        var config = new MetricsConfig();
        var registry = new SimpleMeterRegistry();
        var proxyFactory = new AspectJProxyFactory(new TimedTarget());
        proxyFactory.addAspect(config.timedAspect(registry));
        TimedTarget proxy = proxyFactory.getProxy();

        proxy.doWork();
        proxy.doWork();

        var timer = registry.find("wealthview.test.timed").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
    }

    static class TimedTarget {

        @Timed("wealthview.test.timed")
        public void doWork() {
            // body intentionally empty — only the timing interception matters
        }
    }
}
