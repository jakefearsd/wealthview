package com.wealthview.core.property.dto;

import com.wealthview.persistence.entity.PropertyEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        BigDecimal usefulLifeYears
) {
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
                entity.getUsefulLifeYears()
        );
    }
}
