package com.wealthview.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class SampleDataInitializerTest {

    @Test
    void profileAnnotation_excludesProd() {
        var profile = SampleDataInitializer.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value())
                .as("demo user must not be seeded into prod — it creates a known-password admin account")
                .doesNotContain("prod");
    }

    @Test
    void profileAnnotation_isDevAndDockerOnly() {
        var profile = SampleDataInitializer.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "docker");
    }
}
