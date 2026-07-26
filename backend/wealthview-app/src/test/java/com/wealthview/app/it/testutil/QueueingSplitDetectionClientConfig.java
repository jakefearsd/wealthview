package com.wealthview.app.it.testutil;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.wealthview.core.split.SplitDetectionClient;

/**
 * Registers a single {@link QueueingSplitDetectionClient} bean. Its concrete
 * type is assignable to {@link SplitDetectionClient}, so this one bean
 * satisfies both {@code FinnhubConfig}'s {@code @ConditionalOnBean} guard
 * (which flips on the sync/backfill services under test) and the test
 * class's own {@code @Autowired QueueingSplitDetectionClient} field used to
 * queue/fail/reset it.
 *
 * <p>An earlier version also declared a second {@code @Bean} returning the
 * same instance typed as {@code SplitDetectionClient}, mirroring the two
 * stubs this replaces. That produced a genuine ambiguity: once Spring
 * resolves the second bean's actual runtime type (a {@code
 * QueueingSplitDetectionClient}), autowiring the concrete type below finds
 * two matching bean definitions. A single bean of the concrete type is
 * sufficient — Spring's type-based autowiring already matches a subtype bean
 * against a supertype-typed dependency.
 */
@TestConfiguration
public class QueueingSplitDetectionClientConfig {

    @Bean
    public QueueingSplitDetectionClient queueingSplitDetectionClient() {
        return new QueueingSplitDetectionClient();
    }
}
