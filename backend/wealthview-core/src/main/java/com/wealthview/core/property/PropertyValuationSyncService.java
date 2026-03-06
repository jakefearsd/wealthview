package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.repository.PropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    @Scheduled(cron = "${app.zillow.sync-cron:0 0 6 * * SUN}")
    public void syncAll() {
        log.info("Starting property valuation sync");
        var properties = propertyRepository.findAll();
        int success = 0;
        int skipped = 0;

        for (var property : properties) {
            try {
                var result = valuationClient.getValuation(property.getAddress());
                if (result.isPresent()) {
                    valuationService.recordValuation(
                            property.getTenantId(), property.getId(),
                            result.get().date(), result.get().value(), "zillow");
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

        log.info("Property valuation sync complete: {} updated, {} skipped", success, skipped);
    }

    public void syncSingleProperty(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var result = valuationClient.getValuation(property.getAddress());
        if (result.isPresent()) {
            valuationService.recordValuation(tenantId, propertyId,
                    result.get().date(), result.get().value(), "zillow");
            log.info("Synced valuation for property {}: {}", propertyId, result.get().value());
        } else {
            log.warn("No valuation available for property {}", propertyId);
        }
    }
}
