package com.wealthview.core.projection.dto;

import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;

import java.math.BigDecimal;
import java.util.UUID;

public record ScenarioIncomeSourceResponse(
        UUID incomeSourceId,
        String name,
        String incomeType,
        BigDecimal annualAmount,
        BigDecimal overrideAnnualAmount,
        BigDecimal effectiveAmount,
        BigDecimal annualNetCashFlow,
        int startAge,
        Integer endAge,
        BigDecimal inflationRate,
        boolean oneTime) {

    /**
     * Builds a response for a scenario income-source link. The effective amount and
     * rental net cash flow are computed by the service (the latter requires cross-entity
     * data from the linked property), so they are passed in rather than derived here.
     */
    public static ScenarioIncomeSourceResponse from(ScenarioIncomeSourceEntity link,
                                                    BigDecimal effectiveAmount,
                                                    BigDecimal annualNetCashFlow) {
        var src = link.getIncomeSource();
        return new ScenarioIncomeSourceResponse(
                src.getId(), src.getName(), src.getIncomeType(),
                src.getAnnualAmount(), link.getOverrideAnnualAmount(), effectiveAmount,
                annualNetCashFlow,
                src.getStartAge(), src.getEndAge(),
                src.getInflationRate(), src.isOneTime());
    }
}
