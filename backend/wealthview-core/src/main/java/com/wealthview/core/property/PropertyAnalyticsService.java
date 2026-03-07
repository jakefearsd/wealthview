package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.EquityGrowthPoint;
import com.wealthview.core.property.dto.MortgageProgress;
import com.wealthview.core.property.dto.PropertyAnalyticsResponse;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.entity.PropertyValuationEntity;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
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

@Service
public class PropertyAnalyticsService {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PropertyRepository propertyRepository;
    private final PropertyIncomeRepository incomeRepository;
    private final PropertyExpenseRepository expenseRepository;
    private final PropertyValuationRepository valuationRepository;

    public PropertyAnalyticsService(PropertyRepository propertyRepository,
                                     PropertyIncomeRepository incomeRepository,
                                     PropertyExpenseRepository expenseRepository,
                                     PropertyValuationRepository valuationRepository) {
        this.propertyRepository = propertyRepository;
        this.incomeRepository = incomeRepository;
        this.expenseRepository = expenseRepository;
        this.valuationRepository = valuationRepository;
    }

    @Transactional(readOnly = true)
    public PropertyAnalyticsResponse getAnalytics(UUID tenantId, UUID propertyId, Integer year) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var totalAppreciation = property.getCurrentValue().subtract(property.getPurchasePrice());
        var appreciationPercent = property.getPurchasePrice().compareTo(BigDecimal.ZERO) != 0
                ? totalAppreciation.multiply(new BigDecimal("100"))
                    .divide(property.getPurchasePrice(), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

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
            var dateRange = computeDateRange(year);
            var rangeFrom = dateRange[0];
            var rangeTo = dateRange[1];

            var incomes = incomeRepository.findByProperty_IdAndDateBetween(propertyId, rangeFrom, rangeTo);
            var totalIncome = incomes.stream()
                    .map(PropertyIncomeEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            var operatingExpenses = expenseRepository
                    .findByProperty_IdAndDateBetweenAndCategoryNot(propertyId, rangeFrom, rangeTo, "mortgage");
            var totalOperatingExpenses = operatingExpenses.stream()
                    .map(PropertyExpenseEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            annualNoi = totalIncome.subtract(totalOperatingExpenses);
            capRate = property.getCurrentValue().compareTo(BigDecimal.ZERO) != 0
                    ? annualNoi.multiply(new BigDecimal("100"))
                        .divide(property.getCurrentValue(), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            var allExpenses = expenseRepository.findByProperty_IdAndDateBetween(propertyId, rangeFrom, rangeTo);
            var totalAllExpenses = allExpenses.stream()
                    .map(PropertyExpenseEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            annualNetCashFlow = totalIncome.subtract(totalAllExpenses);

            totalCashInvested = property.hasLoanDetails()
                    ? property.getPurchasePrice().subtract(property.getLoanAmount())
                    : property.getPurchasePrice();

            cashOnCashReturn = totalCashInvested.compareTo(BigDecimal.ZERO) != 0
                    ? annualNetCashFlow.multiply(new BigDecimal("100"))
                        .divide(totalCashInvested, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
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
        var percentPaidOff = property.getLoanAmount().compareTo(BigDecimal.ZERO) != 0
                ? principalPaid.multiply(new BigDecimal("100"))
                    .divide(property.getLoanAmount(), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

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
