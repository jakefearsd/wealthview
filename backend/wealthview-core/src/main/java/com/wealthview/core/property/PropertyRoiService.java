package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.HoldScenarioResult;
import com.wealthview.core.property.dto.RoiAnalysisResponse;
import com.wealthview.core.property.dto.SellScenarioResult;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class PropertyRoiService {

    private static final Logger log = LoggerFactory.getLogger(PropertyRoiService.class);

    private static final BigDecimal SELLING_COST_RATE = new BigDecimal("0.06");
    private static final BigDecimal CAPITAL_GAINS_RATE = new BigDecimal("0.15");
    private static final BigDecimal DEPRECIATION_RECAPTURE_RATE = new BigDecimal("0.25");
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final PropertyRepository propertyRepository;
    private final IncomeSourceRepository incomeSourceRepository;
    private final DepreciationCalculator depreciationCalculator;

    public PropertyRoiService(PropertyRepository propertyRepository,
                              IncomeSourceRepository incomeSourceRepository,
                              DepreciationCalculator depreciationCalculator) {
        this.propertyRepository = propertyRepository;
        this.incomeSourceRepository = incomeSourceRepository;
        this.depreciationCalculator = depreciationCalculator;
    }

    @Transactional(readOnly = true)
    public RoiAnalysisResponse computeRoiAnalysis(UUID tenantId, UUID propertyId,
                                                    UUID incomeSourceId, int years,
                                                    BigDecimal investmentReturn,
                                                    BigDecimal rentGrowth,
                                                    BigDecimal expenseInflation) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var incomeSource = incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSourceId)
                .orElseThrow(() -> new EntityNotFoundException("Income source not found"));

        var sell = computeSellScenario(property.getCurrentValue(), property.getPurchasePrice(),
                property.getDepreciationMethod(), property.getLandValue(),
                property.getInServiceDate(), property.getUsefulLifeYears(),
                property.getCostSegAllocations(), property.getBonusDepreciationRate(),
                property.getCostSegStudyYear(),
                investmentReturn, years);

        var hold = computeHoldScenario(property, incomeSource.getAnnualAmount(), years,
                rentGrowth, expenseInflation);

        var sellEnding = sell.endingNetWorth();
        var holdEnding = hold.endingNetWorth();
        String advantage;
        BigDecimal advantageAmount;
        if (sellEnding.compareTo(holdEnding) > 0) {
            advantage = "sell";
            advantageAmount = sellEnding.subtract(holdEnding);
        } else if (holdEnding.compareTo(sellEnding) > 0) {
            advantage = "hold";
            advantageAmount = holdEnding.subtract(sellEnding);
        } else {
            advantage = "neutral";
            advantageAmount = BigDecimal.ZERO;
        }

        log.info("ROI analysis computed for property {} (tenant {}): advantage={}, amount={}",
                propertyId, tenantId, advantage, advantageAmount);

        return new RoiAnalysisResponse(
                incomeSource.getName(),
                incomeSource.getAnnualAmount(),
                years,
                hold,
                sell,
                advantage,
                advantageAmount
        );
    }

    private SellScenarioResult computeSellScenario(BigDecimal currentValue, BigDecimal purchasePrice,
                                                     String depreciationMethod, BigDecimal landValue,
                                                     LocalDate inServiceDate, BigDecimal usefulLifeYears,
                                                     String costSegAllocationsJson,
                                                     BigDecimal bonusDepreciationRate,
                                                     Integer costSegStudyYear,
                                                     BigDecimal investmentReturn, int years) {
        var grossProceeds = currentValue;
        var sellingCosts = grossProceeds.multiply(SELLING_COST_RATE).setScale(SCALE, ROUNDING);

        var accumulatedDepreciation = computeAccumulatedDepreciation(
                depreciationMethod, purchasePrice, landValue, inServiceDate,
                usefulLifeYears, costSegAllocationsJson, bonusDepreciationRate, costSegStudyYear);

        var depreciationRecaptureTax = accumulatedDepreciation
                .multiply(DEPRECIATION_RECAPTURE_RATE).setScale(SCALE, ROUNDING);

        var totalGain = grossProceeds.subtract(purchasePrice).subtract(sellingCosts);

        var ltcgPortion = totalGain.subtract(accumulatedDepreciation).max(BigDecimal.ZERO);
        var capitalGainsTax = ltcgPortion.multiply(CAPITAL_GAINS_RATE).setScale(SCALE, ROUNDING);

        var netProceeds = grossProceeds
                .subtract(sellingCosts)
                .subtract(depreciationRecaptureTax)
                .subtract(capitalGainsTax);

        var endingNetWorth = compound(netProceeds, investmentReturn, years);

        return new SellScenarioResult(grossProceeds, sellingCosts, depreciationRecaptureTax,
                capitalGainsTax, netProceeds, endingNetWorth);
    }

    private BigDecimal computeAccumulatedDepreciation(String depreciationMethod,
                                                        BigDecimal purchasePrice,
                                                        BigDecimal landValue,
                                                        LocalDate inServiceDate,
                                                        BigDecimal usefulLifeYears,
                                                        String costSegAllocationsJson,
                                                        BigDecimal bonusDepreciationRate,
                                                        Integer costSegStudyYear) {
        if ("none".equals(depreciationMethod) || depreciationMethod == null) {
            return BigDecimal.ZERO;
        }

        var effectiveLandValue = landValue != null ? landValue : BigDecimal.ZERO;
        int currentYear = LocalDate.now().getYear();

        Map<Integer, BigDecimal> schedule;
        if ("cost_segregation".equals(depreciationMethod)) {
            var allocations = PropertyService.parseCostSegAllocations(costSegAllocationsJson);
            schedule = depreciationCalculator.computeCostSegregation(
                    allocations, bonusDepreciationRate, inServiceDate, costSegStudyYear);
        } else {
            // straight_line
            schedule = depreciationCalculator.computeStraightLine(
                    purchasePrice, effectiveLandValue, inServiceDate, usefulLifeYears);
        }

        var accumulated = BigDecimal.ZERO;
        for (var entry : schedule.entrySet()) {
            if (entry.getKey() <= currentYear) {
                accumulated = accumulated.add(entry.getValue());
            }
        }
        return accumulated.setScale(SCALE, ROUNDING);
    }

    HoldScenarioResult computeHoldScenario(PropertyEntity property,
                                             BigDecimal annualRent, int years,
                                             BigDecimal rentGrowth,
                                             BigDecimal expenseInflation) {
        var appreciationRate = property.getAnnualAppreciationRate() != null
                ? property.getAnnualAppreciationRate() : BigDecimal.ZERO;

        // Project property value
        var endingPropertyValue = compound(property.getCurrentValue(), appreciationRate, years);

        // Project mortgage balance
        BigDecimal endingMortgageBalance;
        BigDecimal annualMortgagePayment;
        if (property.hasLoanDetails()) {
            var futureDate = LocalDate.now().plusYears(years);
            endingMortgageBalance = AmortizationCalculator.remainingBalance(
                    property.getLoanAmount(), property.getAnnualInterestRate(),
                    property.getLoanTermMonths(), property.getLoanStartDate(), futureDate);
            annualMortgagePayment = AmortizationCalculator.monthlyPayment(
                    property.getLoanAmount(), property.getAnnualInterestRate(),
                    property.getLoanTermMonths()).multiply(new BigDecimal("12"));
        } else {
            endingMortgageBalance = BigDecimal.ZERO;
            annualMortgagePayment = BigDecimal.ZERO;
        }

        // Annual operating expenses from property entity
        var baseExpenses = sumNullable(
                property.getAnnualPropertyTax(),
                property.getAnnualInsuranceCost(),
                property.getAnnualMaintenanceCost());

        // Accumulate net cash flow year by year
        var cumulativeNetCashFlow = BigDecimal.ZERO;
        var currentRent = annualRent;
        var currentExpenses = baseExpenses;
        var rentMultiplier = BigDecimal.ONE.add(rentGrowth);
        var expenseMultiplier = BigDecimal.ONE.add(expenseInflation);

        for (int y = 0; y < years; y++) {
            // Mortgage payment stops once loan is paid off
            BigDecimal mortgageThisYear = BigDecimal.ZERO;
            if (property.hasLoanDetails()) {
                var yearStartDate = LocalDate.now().plusYears(y);
                var yearStartBalance = AmortizationCalculator.remainingBalance(
                        property.getLoanAmount(), property.getAnnualInterestRate(),
                        property.getLoanTermMonths(), property.getLoanStartDate(),
                        yearStartDate);
                if (yearStartBalance.compareTo(BigDecimal.ZERO) > 0) {
                    mortgageThisYear = annualMortgagePayment;
                }
            }

            var netCashFlow = currentRent.subtract(currentExpenses).subtract(mortgageThisYear);
            cumulativeNetCashFlow = cumulativeNetCashFlow.add(netCashFlow);

            currentRent = currentRent.multiply(rentMultiplier);
            currentExpenses = currentExpenses.multiply(expenseMultiplier);
        }

        cumulativeNetCashFlow = cumulativeNetCashFlow.setScale(SCALE, ROUNDING);
        endingPropertyValue = endingPropertyValue.setScale(SCALE, ROUNDING);
        endingMortgageBalance = endingMortgageBalance.setScale(SCALE, ROUNDING);

        var endingNetWorth = endingPropertyValue
                .subtract(endingMortgageBalance)
                .add(cumulativeNetCashFlow);

        return new HoldScenarioResult(endingPropertyValue, endingMortgageBalance,
                cumulativeNetCashFlow, endingNetWorth);
    }

    private BigDecimal sumNullable(BigDecimal... values) {
        var sum = BigDecimal.ZERO;
        for (var v : values) {
            if (v != null) {
                sum = sum.add(v);
            }
        }
        return sum;
    }

    private BigDecimal compound(BigDecimal principal, BigDecimal annualRate, int years) {
        var factor = BigDecimal.ONE.add(annualRate).pow(years);
        return principal.multiply(factor).setScale(SCALE, ROUNDING);
    }
}
