package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.PropertyValuationResponse;
import com.wealthview.persistence.entity.PropertyValuationEntity;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.PropertyValuationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PropertyValuationService {

    private static final Logger log = LoggerFactory.getLogger(PropertyValuationService.class);

    private final PropertyValuationRepository valuationRepository;
    private final PropertyRepository propertyRepository;

    public PropertyValuationService(PropertyValuationRepository valuationRepository,
                                     PropertyRepository propertyRepository) {
        this.valuationRepository = valuationRepository;
        this.propertyRepository = propertyRepository;
    }

    @Transactional
    public PropertyValuationResponse recordValuation(UUID tenantId, UUID propertyId,
                                                      LocalDate date, BigDecimal value, String source) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var existing = valuationRepository.findByProperty_IdAndSourceAndValuationDate(
                propertyId, source, date);

        PropertyValuationEntity valuation;
        if (existing.isPresent()) {
            valuation = existing.orElseThrow();
            valuation.setValue(value);
            valuation.setUpdatedAt(OffsetDateTime.now());
            log.info("Updated {} valuation for property {} on {}", source, propertyId, date);
        } else {
            valuation = new PropertyValuationEntity(property, property.getTenant(), date, value, source);
            log.info("Recorded {} valuation for property {} on {}: {}", source, propertyId, date, value);
        }
        valuation = valuationRepository.save(valuation);

        property.setCurrentValue(value);
        property.setUpdatedAt(OffsetDateTime.now());
        propertyRepository.save(property);

        return PropertyValuationResponse.from(valuation);
    }

    @Transactional(readOnly = true)
    public List<PropertyValuationResponse> getHistory(UUID tenantId, UUID propertyId) {
        propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        return valuationRepository.findByProperty_IdAndTenant_IdOrderByValuationDateDesc(propertyId, tenantId)
                .stream()
                .map(PropertyValuationResponse::from)
                .toList();
    }
}
