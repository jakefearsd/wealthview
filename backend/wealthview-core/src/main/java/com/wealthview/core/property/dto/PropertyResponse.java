package com.wealthview.core.property.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.persistence.entity.PropertyEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PropertyResponse(
        UUID id,
        String address,
        BigDecimal purchasePrice,
        LocalDate purchaseDate,
        BigDecimal currentValue,
        BigDecimal mortgageBalance,
        BigDecimal equity,
        BigDecimal loanAmount,
        BigDecimal annualInterestRate,
        Integer loanTermMonths,
        LocalDate loanStartDate,
        boolean hasLoanDetails,
        boolean useComputedBalance,
        String propertyType,
        BigDecimal annualAppreciationRate,
        BigDecimal annualPropertyTax,
        BigDecimal annualInsuranceCost,
        BigDecimal annualMaintenanceCost,
        LocalDate inServiceDate,
        BigDecimal landValue,
        String depreciationMethod,
        BigDecimal usefulLifeYears,
        List<CostSegAllocation> costSegAllocations,
        BigDecimal bonusDepreciationRate,
        Integer costSegStudyYear
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PropertyResponse from(PropertyEntity entity, BigDecimal effectiveMortgageBalance) {
        var equity = entity.getCurrentValue().subtract(effectiveMortgageBalance);
        return new PropertyResponse(
                entity.getId(),
                entity.getAddress(),
                entity.getPurchasePrice(),
                entity.getPurchaseDate(),
                entity.getCurrentValue(),
                effectiveMortgageBalance,
                equity,
                entity.getLoanAmount(),
                entity.getAnnualInterestRate(),
                entity.getLoanTermMonths(),
                entity.getLoanStartDate(),
                entity.hasLoanDetails(),
                entity.isUseComputedBalance(),
                entity.getPropertyType(),
                entity.getAnnualAppreciationRate(),
                entity.getAnnualPropertyTax(),
                entity.getAnnualInsuranceCost(),
                entity.getAnnualMaintenanceCost(),
                entity.getInServiceDate(),
                entity.getLandValue(),
                entity.getDepreciationMethod(),
                entity.getUsefulLifeYears(),
                parseCostSegAllocations(entity.getCostSegAllocations()),
                entity.getBonusDepreciationRate(),
                entity.getCostSegStudyYear()
        );
    }

    private static List<CostSegAllocation> parseCostSegAllocations(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
