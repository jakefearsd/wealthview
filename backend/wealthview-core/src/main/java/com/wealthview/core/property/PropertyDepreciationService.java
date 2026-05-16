package com.wealthview.core.property;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.property.dto.CostSegAllocation;
import com.wealthview.core.property.dto.DepreciationScheduleResult;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.persistence.entity.PropertyEntity;

/**
 * Coordinates depreciation configuration for properties.
 *
 * <p>Extracted from {@code PropertyService} as the depreciation/cost-segregation
 * collaborator: it owns validation and persistence of depreciation fields on a
 * {@link PropertyEntity} (applied during create/update), the cost-segregation
 * allocation JSON (de)serialization, and delegates read-side schedule construction
 * to {@link DepreciationScheduleBuilder}. It performs no data access itself —
 * {@code PropertyService} keeps the {@code @Transactional} boundary and tenant-scoped
 * lookups, then hands the resolved entity to this collaborator.
 */
class PropertyDepreciationService {

    private static final Set<String> VALID_DEPRECIATION_METHODS =
            Set.of("none", "straight_line", "cost_segregation");
    private static final Set<String> VALID_ASSET_CLASSES = Set.of("5yr", "7yr", "15yr", "27_5yr");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DepreciationScheduleBuilder scheduleBuilder;

    PropertyDepreciationService(DepreciationCalculator depreciationCalculator) {
        this.scheduleBuilder = new DepreciationScheduleBuilder(depreciationCalculator);
    }

    void applyDepreciationFields(PropertyEntity property, PropertyRequest request) {
        var method = request.depreciationMethod();
        if (method == null) {
            method = "none";
        }
        if (!VALID_DEPRECIATION_METHODS.contains(method)) {
            throw new IllegalArgumentException(
                    "Invalid depreciation method: " + method + ". Must be one of: " + VALID_DEPRECIATION_METHODS);
        }
        if (!"none".equals(method) && request.inServiceDate() == null) {
            throw new IllegalArgumentException(
                    "in_service_date is required when depreciation method is " + method);
        }
        property.setDepreciationMethod(method);
        property.setInServiceDate(request.inServiceDate());
        property.setLandValue(request.landValue());
        if (request.usefulLifeYears() != null) {
            property.setUsefulLifeYears(request.usefulLifeYears());
        }

        if ("cost_segregation".equals(method)) {
            applyCostSegFields(property, request);
        } else {
            property.setCostSegAllocations("[]");
            property.setBonusDepreciationRate(BigDecimal.ONE);
            property.setCostSegStudyYear(null);
        }
    }

    private void applyCostSegFields(PropertyEntity property, PropertyRequest request) {
        var allocations = request.costSegAllocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cost segregation allocations are required when depreciation method is cost_segregation");
        }

        var landValue = request.landValue() != null ? request.landValue() : BigDecimal.ZERO;
        var depreciableBasis = request.purchasePrice().subtract(landValue);

        validateAssetClasses(allocations);
        validateAllocationSum(allocations, depreciableBasis);

        try {
            property.setCostSegAllocations(MAPPER.writeValueAsString(allocations));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize cost seg allocations", e);
        }
        property.setBonusDepreciationRate(
                request.bonusDepreciationRate() != null ? request.bonusDepreciationRate() : BigDecimal.ONE);
        property.setCostSegStudyYear(request.costSegStudyYear());
    }

    private void validateAssetClasses(List<CostSegAllocation> allocations) {
        for (var alloc : allocations) {
            if (!VALID_ASSET_CLASSES.contains(alloc.assetClass())) {
                throw new IllegalArgumentException(
                        "Invalid asset class: " + alloc.assetClass()
                                + ". Must be one of: " + VALID_ASSET_CLASSES);
            }
            if (alloc.allocation().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "Allocation for " + alloc.assetClass() + " must be non-negative");
            }
        }
    }

    private void validateAllocationSum(List<CostSegAllocation> allocations, BigDecimal depreciableBasis) {
        var sum = allocations.stream()
                .map(CostSegAllocation::allocation)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(depreciableBasis) != 0) {
            throw new IllegalArgumentException(
                    "Cost segregation allocations (" + sum
                            + ") must equal depreciable basis (" + depreciableBasis + ")");
        }
    }

    DepreciationScheduleResult buildSchedule(PropertyEntity property) {
        return scheduleBuilder.buildSchedule(property);
    }

    static List<CostSegAllocation> parseCostSegAllocations(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse cost seg allocations", e);
        }
    }
}
