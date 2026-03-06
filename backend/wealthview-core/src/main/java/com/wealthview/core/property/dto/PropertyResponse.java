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
        boolean useComputedBalance
        // TODO: accumulatedDepreciation, bookValue
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
                entity.isUseComputedBalance()
        );
    }
}
