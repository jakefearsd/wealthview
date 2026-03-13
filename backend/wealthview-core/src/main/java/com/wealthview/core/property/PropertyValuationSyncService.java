package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.ValuationRefreshResponse;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.PropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@ConditionalOnBean(PropertyValuationClient.class)
public class PropertyValuationSyncService {

    private static final Logger log = LoggerFactory.getLogger(PropertyValuationSyncService.class);

    private final PropertyRepository propertyRepository;
    private final PropertyValuationClient valuationClient;
    private final PropertyValuationService valuationService;

    public PropertyValuationSyncService(PropertyRepository propertyRepository,
                                         PropertyValuationClient valuationClient,
                                         PropertyValuationService valuationService) {
        this.propertyRepository = propertyRepository;
        this.valuationClient = valuationClient;
        this.valuationService = valuationService;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // intentional per-property resilience
    @Scheduled(cron = "${app.zillow.sync-cron:0 0 6 * * SUN}")
    public void syncAll() {
        long startTime = System.currentTimeMillis();
        log.info("Starting property valuation sync");
        var properties = propertyRepository.findAll();
        int success = 0;
        int skipped = 0;

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
                log.warn("Failed to sync valuation for property {}: {}",
                        property.getId(), e.getMessage());
                skipped++;
            }
        }

        log.info("Property valuation sync complete: {} updated, {} skipped, {}ms",
                success, skipped, System.currentTimeMillis() - startTime);
    }

    @Transactional
    public ValuationRefreshResponse refreshProperty(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

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
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

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
        property.setUpdatedAt(OffsetDateTime.now());
        propertyRepository.save(property);
    }
}
