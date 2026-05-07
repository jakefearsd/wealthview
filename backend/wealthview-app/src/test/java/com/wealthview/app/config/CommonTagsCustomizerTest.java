package com.wealthview.app.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonTagsCustomizerTest {

    @Test
    void customizer_addsApplicationAndEnvTags() {
        var customizer = new MetricsConfig.CommonTagsCustomizer("dev");
        var registry = new SimpleMeterRegistry();

        customizer.customize(registry);
        registry.counter("test.counter").increment();
        var tags = registry.find("test.counter").meter().getId().getTags();

        assertThat(tags).extracting("key", "value")
                .contains(tuple("application", "wealthview"))
                .contains(tuple("env", "dev"));
    }

    @Test
    void customizer_doesNotAddTenantOrUserTags() {
        var customizer = new MetricsConfig.CommonTagsCustomizer("prod");
        var registry = new SimpleMeterRegistry();

        customizer.customize(registry);
        registry.counter("test.counter").increment();
        var tagKeys = registry.find("test.counter").meter().getId().getTags()
                .stream().map(io.micrometer.core.instrument.Tag::getKey).toList();

        assertThat(tagKeys).doesNotContain("tenantId", "userId", "tenant_id", "user_id");
    }

    @Test
    void customizer_unknownEnvDefaultsToUnknown() {
        var customizer = new MetricsConfig.CommonTagsCustomizer(null);
        var registry = new SimpleMeterRegistry();

        customizer.customize(registry);
        registry.counter("test.counter").increment();
        var tags = registry.find("test.counter").meter().getId().getTags();

        assertThat(tags).extracting("key", "value")
                .contains(tuple("env", "unknown"));
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
