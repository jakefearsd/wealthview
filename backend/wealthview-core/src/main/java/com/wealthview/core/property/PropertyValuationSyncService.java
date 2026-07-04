package com.wealthview.core.property;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.common.Entities;
import com.wealthview.core.property.dto.ValuationRefreshResponse;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.PropertyRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;

@Service
@ConditionalOnBean(PropertyValuationClient.class)
public class PropertyValuationSyncService {

    private static final Logger log = LoggerFactory.getLogger(PropertyValuationSyncService.class);

    private final PropertyRepository propertyRepository;
    private final PropertyValuationClient valuationClient;
    private final PropertyValuationService valuationService;
    private final MeterRegistry meterRegistry;
    private final java.util.concurrent.atomic.AtomicLong lastSuccessEpochSeconds =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public PropertyValuationSyncService(PropertyRepository propertyRepository,
                                         PropertyValuationClient valuationClient,
                                         PropertyValuationService valuationService,
                                         MeterRegistry meterRegistry) {
        this.propertyRepository = propertyRepository;
        this.valuationClient = valuationClient;
        this.valuationService = valuationService;
        this.meterRegistry = meterRegistry;
        io.micrometer.core.instrument.Gauge.builder("wealthview.scheduled.last_success_seconds",
                        lastSuccessEpochSeconds, java.util.concurrent.atomic.AtomicLong::doubleValue)
                .tag("job", "propertyValuationSync")
                .description("Unix epoch seconds of the most recent successful run; 0 if never succeeded")
                .register(meterRegistry);
    }

    @Timed("wealthview.property.valuation.sync")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // intentional per-property resilience
    @Scheduled(cron = "${app.zillow.sync-cron:0 0 6 * * SUN}")
    public void syncAll() {
        MDC.put("operation", "propertyValuationSync");
        MDC.put("requestId", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        boolean failed = false;
        try {
            long startTime = System.currentTimeMillis();
            log.info("Starting property valuation sync");
            var tenantIds = propertyRepository.findDistinctTenantIds();
            int success = 0;
            int skipped = 0;

            for (var tenantId : tenantIds) {
                var properties = propertyRepository.findByTenant_Id(tenantId);
                for (var property : properties) {
                    try {
                        var resultOpt = valuationClient.getValuation(property.getAddress());
                        if (resultOpt.isPresent()) {
                            var result = resultOpt.orElseThrow();
                            valuationService.recordValuation(
                                    property.getTenantId(), property.getId(),
                                    result.date(), result.value(), "zillow");
                            success++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to sync valuation for property {}",
                                property.getId(), e);
                        skipped++;
                    }
                }
            }

            meterRegistry.counter("wealthview.property.valuations", "status", "success").increment(success);
            meterRegistry.counter("wealthview.property.valuations", "status", "skipped").increment(skipped);
            log.info("Property valuation sync complete: {} updated, {} skipped, {}ms",
                    success, skipped, System.currentTimeMillis() - startTime);
        } catch (RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            meterRegistry.counter("wealthview.scheduled.runs",
                    "job", "propertyValuationSync", "status", failed ? "failure" : "success").increment();
            if (!failed) {
                lastSuccessEpochSeconds.set(java.time.Instant.now().getEpochSecond());
            }
            MDC.remove("operation");
            MDC.remove("requestId");
        }
    }

    @Transactional
    public ValuationRefreshResponse refreshProperty(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        if (property.getZillowZpid() != null) {
            return fetchByZpid(tenantId, propertyId, property.getZillowZpid());
        }

        var candidates = valuationClient.searchProperties(property.getAddress());

        if (candidates.isEmpty()) {
            log.warn("No Zillow results for property {} (address: {})", propertyId, property.getAddress());
            return ValuationRefreshResponse.noResults();
        }

        if (candidates.size() == 1) {
            var candidate = candidates.get(0);
            storeZpid(property, candidate.zpid());
            return fetchByZpid(tenantId, propertyId, candidate.zpid());
        }

        log.info("Multiple Zillow matches for property {} (address: {}): {} candidates",
                propertyId, property.getAddress(), candidates.size());
        return ValuationRefreshResponse.multipleMatches(candidates);
    }

    @Transactional
    public ValuationRefreshResponse selectZpid(UUID tenantId, UUID propertyId, String zpid) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        storeZpid(property, zpid);
        return fetchByZpid(tenantId, propertyId, zpid);
    }

    private ValuationRefreshResponse fetchByZpid(UUID tenantId, UUID propertyId, String zpid) {
        var resultOpt = valuationClient.getValuationByZpid(zpid);
        if (resultOpt.isPresent()) {
            var result = resultOpt.orElseThrow();
            valuationService.recordValuation(tenantId, propertyId,
                    result.date(), result.value(), "zillow");
            log.info("Synced valuation for property {}: {}", propertyId, result.value());
            return ValuationRefreshResponse.updated(result.value());
        }

        log.warn("No valuation available for property {} (zpid: {})", propertyId, zpid);
        return ValuationRefreshResponse.noResults();
    }

    private void storeZpid(PropertyEntity property, String zpid) {
        property.setZillowZpid(zpid);
        propertyRepository.save(property);
    }
}
