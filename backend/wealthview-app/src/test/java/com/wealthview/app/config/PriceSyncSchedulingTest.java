package com.wealthview.app.config;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import com.wealthview.core.pricefeed.PriceSyncService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the daily Finnhub price sync against being scheduled more than once.
 *
 * <p>{@code PriceSyncService.syncDailyPrices()} owns its own configurable
 * {@code @Scheduled} trigger ({@code app.finnhub.sync-cron}). A second bean that also
 * schedules the sweep — such as the removed {@code PriceSyncScheduler} wrapper — makes the
 * full symbol sweep run twice every weekday and silently burns double the Finnhub quota.
 *
 * <p>This test scans every Spring component under {@code com.wealthview} for
 * {@code @Scheduled} methods and asserts exactly one of them triggers the daily price sync.
 * A method counts as a trigger when it either <em>is</em> {@code syncDailyPrices} or is
 * declared on a bean that depends on {@link PriceSyncService} — the only reason such a
 * scheduled bean would exist is to delegate to it.
 */
class PriceSyncSchedulingTest {

    private static final String BASE_PACKAGE = "com.wealthview";
    private static final String DAILY_SYNC_METHOD = "syncDailyPrices";

    @Test
    void scheduledTriggersForTheDailyPriceSync_isExactlyTheServiceOwnJob() {
        var triggers = scheduledTriggersForDailyPriceSync();

        assertThat(triggers).containsExactly(PriceSyncService.class.getName() + "#" + DAILY_SYNC_METHOD);
    }

    @Test
    void syncDailyPrices_carriesTheConfigurableScheduledAnnotation() throws NoSuchMethodException {
        var scheduled = PriceSyncService.class.getDeclaredMethod(DAILY_SYNC_METHOD).getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.finnhub.sync-cron:0 0 18 * * MON-FRI}");
        assertThat(scheduled.zone()).isEqualTo("America/New_York");
    }

    private List<String> scheduledTriggersForDailyPriceSync() {
        var triggers = new ArrayList<String>();
        for (var candidate : new ConditionAgnosticComponentScanner().findCandidateComponents(BASE_PACKAGE)) {
            var type = ClassUtils.resolveClassName(candidate.getBeanClassName(), getClass().getClassLoader());
            for (var method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class) && triggersDailyPriceSync(type, method)) {
                    triggers.add(type.getName() + "#" + method.getName());
                }
            }
        }
        triggers.sort(Comparator.naturalOrder());
        return triggers;
    }

    private boolean triggersDailyPriceSync(Class<?> type, Method method) {
        if (PriceSyncService.class.equals(type)) {
            return DAILY_SYNC_METHOD.equals(method.getName());
        }
        return dependsOnPriceSyncService(type);
    }

    private boolean dependsOnPriceSyncService(Class<?> type) {
        for (var field : type.getDeclaredFields()) {
            if (PriceSyncService.class.equals(field.getType())) {
                return true;
            }
        }
        for (var constructor : type.getDeclaredConstructors()) {
            for (var parameterType : constructor.getParameterTypes()) {
                if (PriceSyncService.class.equals(parameterType)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Scans for {@code @Component}-meta-annotated classes while ignoring {@code @ConditionalOn*}.
     * The stock provider evaluates conditions during the scan, which would drop
     * {@link PriceSyncService} (gated on {@code app.finnhub.api-key} being set) from a bare
     * unit-test environment. Scheduling wiring is a static property of the code, not of the
     * environment, so conditions are deliberately not applied here.
     */
    private static final class ConditionAgnosticComponentScanner extends ClassPathScanningCandidateComponentProvider {

        private final TypeFilter componentFilter = new AnnotationTypeFilter(Component.class);

        ConditionAgnosticComponentScanner() {
            super(false);
            addIncludeFilter(componentFilter);
        }

        @Override
        protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
            return componentFilter.match(metadataReader, getMetadataReaderFactory());
        }
    }
}
