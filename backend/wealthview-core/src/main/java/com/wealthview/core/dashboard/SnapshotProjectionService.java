package com.wealthview.core.dashboard;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.dashboard.dto.SnapshotProjectionDataPointDto;
import com.wealthview.core.dashboard.dto.SnapshotProjectionResponse;
import com.wealthview.core.portfolio.TheoreticalPortfolioService;
import com.wealthview.core.property.AmortizationCalculator;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SnapshotProjectionService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotProjectionService.class);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int MIN_PROJECTION_YEARS = 5;
    private static final int MAX_PROJECTION_YEARS = 20;
    private static final int MIN_LOOKBACK_YEARS = 1;
    private static final int MAX_LOOKBACK_YEARS = 10;
    private static final long MIN_MONTHS_FOR_CAGR = 3;

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final PropertyRepository propertyRepository;
    private final TheoreticalPortfolioService theoreticalPortfolioService;

    public SnapshotProjectionService(AccountRepository accountRepository,
                                      AccountService accountService,
                                      PropertyRepository propertyRepository,
                                      TheoreticalPortfolioService theoreticalPortfolioService) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.propertyRepository = propertyRepository;
        this.theoreticalPortfolioService = theoreticalPortfolioService;
    }

    @Transactional(readOnly = true)
    public SnapshotProjectionResponse computeProjection(UUID tenantId, int projectionYears, int lookbackYears) {
        var clampedYears = Math.max(MIN_PROJECTION_YEARS, Math.min(MAX_PROJECTION_YEARS, projectionYears));
        var clampedLookback = Math.max(MIN_LOOKBACK_YEARS, Math.min(MAX_LOOKBACK_YEARS, lookbackYears));

        var accounts = accountRepository.findByTenant_Id(tenantId);
        var properties = propertyRepository.findByTenant_Id(tenantId);

        if (accounts.isEmpty() && properties.isEmpty()) {
            return new SnapshotProjectionResponse(List.of(), clampedYears, 0, 0, BigDecimal.ZERO);
        }

        // Compute per-account current value and CAGR
        var accountProjections = new ArrayList<AccountProjection>();
        for (var account : accounts) {
            if ("bank".equals(account.getType())) {
                var balance = accountService.computeBalance(account, tenantId);
                accountProjections.add(new AccountProjection(balance, BigDecimal.ZERO));
            } else {
                var projection = computeInvestmentProjection(tenantId, account, clampedLookback);
                accountProjections.add(projection);
            }
        }

        // Compute weighted portfolio CAGR
        var totalInvestmentValue = accountProjections.stream()
                .map(AccountProjection::currentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var portfolioCagr = computeWeightedCagr(accountProjections, totalInvestmentValue);

        // Build data points
        var today = LocalDate.now();
        var dataPoints = new ArrayList<SnapshotProjectionDataPointDto>();

        for (int year = 0; year <= clampedYears; year++) {
            var date = today.plusYears(year);

            // Project investment accounts
            var investmentValue = BigDecimal.ZERO;
            for (var ap : accountProjections) {
                investmentValue = investmentValue.add(projectValue(ap.currentValue(), ap.cagr(), year));
            }

            // Project properties
            var propertyEquity = BigDecimal.ZERO;
            for (var property : properties) {
                propertyEquity = propertyEquity.add(computeProjectedEquity(property, year, date));
            }

            var totalValue = investmentValue.add(propertyEquity);
            dataPoints.add(new SnapshotProjectionDataPointDto(
                    year, date, totalValue.setScale(4, RoundingMode.HALF_UP),
                    investmentValue.setScale(4, RoundingMode.HALF_UP),
                    propertyEquity.setScale(4, RoundingMode.HALF_UP)));
        }

        log.info("Computed snapshot projection for tenant {}: {} years, {} accounts, {} properties",
                tenantId, clampedYears, accounts.size(), properties.size());

        return new SnapshotProjectionResponse(
                dataPoints, clampedYears, accounts.size(), properties.size(),
                portfolioCagr.setScale(6, RoundingMode.HALF_UP));
    }

    private AccountProjection computeInvestmentProjection(UUID tenantId, AccountEntity account, int lookbackYears) {
        var history = theoreticalPortfolioService.computeHistory(tenantId, account.getId(), lookbackYears);
        var dataPoints = history.dataPoints();

        if (dataPoints.isEmpty()) {
            return new AccountProjection(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        var first = dataPoints.get(0);
        var last = dataPoints.get(dataPoints.size() - 1);
        var currentValue = last.totalValue();

        // Need at least MIN_MONTHS_FOR_CAGR months of data
        var monthsBetween = java.time.temporal.ChronoUnit.MONTHS.between(first.date(), last.date());
        if (monthsBetween < MIN_MONTHS_FOR_CAGR || first.totalValue().compareTo(BigDecimal.ZERO) <= 0) {
            return new AccountProjection(currentValue, BigDecimal.ZERO);
        }

        // CAGR = (endValue / startValue) ^ (1 / years) - 1
        var yearsBetween = new BigDecimal(monthsBetween).divide(new BigDecimal("12"), MC);
        var ratio = last.totalValue().divide(first.totalValue(), MC);
        var exponent = BigDecimal.ONE.divide(yearsBetween, MC);
        var cagr = BigDecimal.valueOf(Math.pow(ratio.doubleValue(), exponent.doubleValue())).subtract(BigDecimal.ONE);

        return new AccountProjection(currentValue, cagr);
    }

    private BigDecimal computeWeightedCagr(List<AccountProjection> projections, BigDecimal totalValue) {
        if (totalValue.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        var weightedSum = BigDecimal.ZERO;
        for (var ap : projections) {
            var weight = ap.currentValue().divide(totalValue, MC);
            weightedSum = weightedSum.add(ap.cagr().multiply(weight, MC));
        }
        return weightedSum;
    }

    private BigDecimal projectValue(BigDecimal currentValue, BigDecimal cagr, int year) {
        if (year == 0) {
            return currentValue;
        }
        var growthFactor = BigDecimal.valueOf(Math.pow(BigDecimal.ONE.add(cagr).doubleValue(), year));
        return currentValue.multiply(growthFactor, MC);
    }

    private BigDecimal computeProjectedEquity(PropertyEntity property, int year, LocalDate asOfDate) {
        // Appreciated value
        var appreciationRate = property.getAnnualAppreciationRate() != null
                ? property.getAnnualAppreciationRate() : BigDecimal.ZERO;
        var appreciatedValue = year == 0
                ? property.getCurrentValue()
                : property.getCurrentValue().multiply(
                        BigDecimal.valueOf(Math.pow(BigDecimal.ONE.add(appreciationRate).doubleValue(), year)), MC);

        // Mortgage balance
        BigDecimal mortgageBalance;
        if (property.hasLoanDetails()) {
            mortgageBalance = AmortizationCalculator.remainingBalance(
                    property.getLoanAmount(),
                    property.getAnnualInterestRate(),
                    property.getLoanTermMonths(),
                    property.getLoanStartDate(),
                    asOfDate);
        } else {
            mortgageBalance = property.getMortgageBalance();
        }

        return appreciatedValue.subtract(mortgageBalance);
    }

    private record AccountProjection(BigDecimal currentValue, BigDecimal cagr) {}
}
