package com.wealthview.core.property;

import com.wealthview.core.common.Entities;
import com.wealthview.core.property.dto.EquityGrowthPoint;
import com.wealthview.core.property.dto.MortgageProgress;
import com.wealthview.core.property.dto.PropertyAnalyticsResponse;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyValuationEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.PropertyValuationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
public class PropertyAnalyticsService {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final BigDecimal BD_100 = new BigDecimal("100");

    private final PropertyRepository propertyRepository;
    private final IncomeSourceRepository incomeSourceRepository;
    private final PropertyExpenseRepository expenseRepository;
    private final PropertyValuationRepository valuationRepository;

    public PropertyAnalyticsService(PropertyRepository propertyRepository,
                                     IncomeSourceRepository incomeSourceRepository,
                                     PropertyExpenseRepository expenseRepository,
                                     PropertyValuationRepository valuationRepository) {
        this.propertyRepository = propertyRepository;
        this.incomeSourceRepository = incomeSourceRepository;
        this.expenseRepository = expenseRepository;
        this.valuationRepository = valuationRepository;
    }

    @Transactional(readOnly = true)
    public PropertyAnalyticsResponse getAnalytics(UUID tenantId, UUID propertyId, Integer year) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        var totalAppreciation = property.getCurrentValue().subtract(property.getPurchasePrice());
        var appreciationPercent = percentageOf(totalAppreciation, property.getPurchasePrice());

        var mortgageProgress = property.hasLoanDetails() ? computeMortgageProgress(property) : null;

        var valuations = valuationRepository
                .findByProperty_IdAndTenant_IdOrderByValuationDateDesc(propertyId, tenantId);
        var equityGrowth = computeEquityGrowth(property, valuations);

        BigDecimal capRate = null;
        BigDecimal annualNoi = null;
        BigDecimal cashOnCashReturn = null;
        BigDecimal annualNetCashFlow = null;
        BigDecimal totalCashInvested = null;

        if ("investment".equals(property.getPropertyType())) {
            var linkedSources = incomeSourceRepository.findByTenant_IdAndProperty_Id(tenantId, propertyId);
            var totalIncome = linkedSources.stream()
                    .map(IncomeSourceEntity::getAnnualAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            boolean hasEntityFields = property.getAnnualPropertyTax() != null
                    || property.getAnnualInsuranceCost() != null
                    || property.getAnnualMaintenanceCost() != null;

            if (hasEntityFields) {
                var totalOperatingExpenses = sumNullable(
                        property.getAnnualPropertyTax(),
                        property.getAnnualInsuranceCost(),
                        property.getAnnualMaintenanceCost());

                annualNoi = totalIncome.subtract(totalOperatingExpenses);
                capRate = percentageOf(annualNoi, property.getCurrentValue());

                var totalAllExpenses = totalOperatingExpenses;
                if (property.hasLoanDetails()) {
                    var annualMortgage = AmortizationCalculator.monthlyPayment(
                            property.getLoanAmount(), property.getAnnualInterestRate(),
                            property.getLoanTermMonths())
                            .multiply(new BigDecimal("12"));
                    totalAllExpenses = totalAllExpenses.add(annualMortgage);
                }

                annualNetCashFlow = totalIncome.subtract(totalAllExpenses);
            } else {
                var dateRange = computeDateRange(year);
                var rangeFrom = dateRange[0];
                var rangeTo = dateRange[1];
                var annualFrom = YearMonth.from(rangeFrom).minusMonths(11).atDay(1);

                var operatingExpenses = expenseRepository
                        .findOverlappingExcludingCategory(propertyId, rangeFrom, rangeTo, annualFrom, "mortgage");
                var totalOperatingExpenses = sumWithProration(operatingExpenses, rangeFrom, rangeTo,
                        PropertyExpenseEntity::getAmount, PropertyExpenseEntity::getFrequency,
                        PropertyExpenseEntity::getDate);

                annualNoi = totalIncome.subtract(totalOperatingExpenses);
                capRate = percentageOf(annualNoi, property.getCurrentValue());

                var allExpenses = expenseRepository.findOverlapping(propertyId, rangeFrom, rangeTo, annualFrom);
                var totalAllExpenses = sumWithProration(allExpenses, rangeFrom, rangeTo,
                        PropertyExpenseEntity::getAmount, PropertyExpenseEntity::getFrequency,
                        PropertyExpenseEntity::getDate);

                annualNetCashFlow = totalIncome.subtract(totalAllExpenses);
            }

            totalCashInvested = property.hasLoanDetails()
                    ? property.getPurchasePrice().subtract(property.getLoanAmount())
                    : property.getPurchasePrice();

            cashOnCashReturn = percentageOf(annualNetCashFlow, totalCashInvested);
        }

        return new PropertyAnalyticsResponse(
                property.getPropertyType(),
                totalAppreciation,
                appreciationPercent,
                mortgageProgress,
                equityGrowth,
                capRate,
                annualNoi,
                cashOnCashReturn,
                annualNetCashFlow,
                totalCashInvested
        );
    }

    private MortgageProgress computeMortgageProgress(PropertyEntity property) {
        var now = LocalDate.now();
        var currentBalance = AmortizationCalculator.remainingBalance(
                property.getLoanAmount(),
                property.getAnnualInterestRate(),
                property.getLoanTermMonths(),
                property.getLoanStartDate(),
                now);

        var principalPaid = property.getLoanAmount().subtract(currentBalance);
        var percentPaidOff = percentageOf(principalPaid, property.getLoanAmount());

        var estimatedPayoffDate = property.getLoanStartDate()
                .plusMonths(property.getLoanTermMonths());

        var monthsRemaining = (int) Math.max(0,
                java.time.temporal.ChronoUnit.MONTHS.between(now, estimatedPayoffDate));

        return new MortgageProgress(
                property.getLoanAmount(),
                currentBalance,
                principalPaid,
                percentPaidOff,
                estimatedPayoffDate,
                monthsRemaining
        );
    }

    private List<EquityGrowthPoint> computeEquityGrowth(PropertyEntity property,
                                                         List<PropertyValuationEntity> valuations) {
        var points = new ArrayList<EquityGrowthPoint>();
        var startMonth = YearMonth.from(property.getPurchaseDate());
        var endMonth = YearMonth.now();
        var currentValue = property.getCurrentValue();

        var current = startMonth;
        while (!current.isAfter(endMonth)) {
            var monthDate = current.atDay(1);

            var propertyValue = findValueForMonth(current, valuations, currentValue);

            BigDecimal mortgageBalance;
            if (property.hasLoanDetails()) {
                mortgageBalance = AmortizationCalculator.remainingBalance(
                        property.getLoanAmount(),
                        property.getAnnualInterestRate(),
                        property.getLoanTermMonths(),
                        property.getLoanStartDate(),
                        monthDate);
            } else {
                mortgageBalance = property.getMortgageBalance();
            }

            var equity = propertyValue.subtract(mortgageBalance);

            points.add(new EquityGrowthPoint(
                    current.format(MONTH_FORMAT),
                    equity,
                    propertyValue,
                    mortgageBalance
            ));

            current = current.plusMonths(1);
        }

        return points;
    }

    private BigDecimal findValueForMonth(YearMonth month, List<PropertyValuationEntity> valuations,
                                          BigDecimal fallbackValue) {
        // Valuations are ordered by date DESC; find the nearest one at or before this month
        var monthEnd = month.atEndOfMonth();
        for (var valuation : valuations) {
            if (!valuation.getValuationDate().isAfter(monthEnd)) {
                return valuation.getValue();
            }
        }
        return fallbackValue;
    }

    private <T> BigDecimal sumWithProration(List<T> entries, LocalDate rangeFrom, LocalDate rangeTo,
                                               Function<T, BigDecimal> amountFn,
                                               Function<T, String> frequencyFn,
                                               Function<T, LocalDate> dateFn) {
        var total = BigDecimal.ZERO;
        var rangeFromMonth = YearMonth.from(rangeFrom);
        var rangeToMonth = YearMonth.from(rangeTo);

        for (var entry : entries) {
            total = total.add(computeEffectiveAmount(
                    amountFn.apply(entry), frequencyFn.apply(entry), dateFn.apply(entry),
                    rangeFromMonth, rangeToMonth));
        }
        return total;
    }

    private BigDecimal computeEffectiveAmount(BigDecimal amount, String frequency, LocalDate entryDate,
                                               YearMonth rangeFrom, YearMonth rangeTo) {
        if (!"annual".equals(frequency)) {
            return amount;
        }

        var entryMonth = YearMonth.from(entryDate);
        var entryEnd = entryMonth.plusMonths(11);

        var overlapStart = entryMonth.isBefore(rangeFrom) ? rangeFrom : entryMonth;
        var overlapEnd = entryEnd.isAfter(rangeTo) ? rangeTo : entryEnd;

        if (overlapStart.isAfter(overlapEnd)) {
            return BigDecimal.ZERO;
        }

        long overlappingMonths = overlapStart.until(overlapEnd, java.time.temporal.ChronoUnit.MONTHS) + 1;
        return amount.multiply(new BigDecimal(overlappingMonths))
                .divide(new BigDecimal("12"), 4, RoundingMode.HALF_UP);
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

    private BigDecimal percentageOf(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(BD_100).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private LocalDate[] computeDateRange(Integer year) {
        if (year != null) {
            return new LocalDate[]{
                    LocalDate.of(year, 1, 1),
                    LocalDate.of(year, 12, 31)
            };
        }
        var now = LocalDate.now();
        return new LocalDate[]{
                now.minusYears(1),
                now
        };
    }
}
