package com.wealthview.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;

/**
 * Observability wiring: shared TimedAspect for {@link io.micrometer.core.annotation.Timed},
 * and a MeterRegistryCustomizer that stamps every meter with deployment-identifying tags.
 *
 * <p>Common tags intentionally exclude {@code tenantId} and {@code userId} — adding them
 * would explode cardinality and leak PII into Prometheus.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    /**
     * Enables {@link io.micrometer.observation.annotation.Observed} on Spring beans.
     * When tracing is enabled, observed methods become spans automatically; when
     * tracing is off the aspect is still cheap (a no-op observation).
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(
            @Value("${spring.profiles.active:unknown}") String activeProfile) {
        return new CommonTagsCustomizer(activeProfile);
    }

    /**
     * Adds {@code application=wealthview} and {@code env=<profile>} as common tags on every meter.
     * Package-visible for testing without spinning up a Spring context.
     */
    static final class CommonTagsCustomizer implements MeterRegistryCustomizer<MeterRegistry> {

        private final String env;

        CommonTagsCustomizer(String activeProfile) {
            this.env = (activeProfile == null || activeProfile.isBlank()) ? "unknown" : activeProfile;
        }

        @Override
        public void customize(MeterRegistry registry) {
            registry.config().meterFilter(MeterFilter.commonTags(java.util.List.of(
                    io.micrometer.core.instrument.Tag.of("application", "wealthview"),
                    io.micrometer.core.instrument.Tag.of("env", env)
            )));
        }
    }
}
