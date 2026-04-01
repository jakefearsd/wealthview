package com.wealthview.core.dashboard;

import com.wealthview.core.dashboard.dto.CombinedPortfolioDataPointDto;
import com.wealthview.core.dashboard.dto.CombinedPortfolioHistoryResponse;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.property.AmortizationCalculator;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyValuationEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.PropertyValuationRepository;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CombinedPortfolioHistoryService {

    private static final Logger log = LoggerFactory.getLogger(CombinedPortfolioHistoryService.class);
    private static final int MIN_YEARS = 1;
    private static final int MAX_YEARS = 10;

    private final AccountRepository accountRepository;
    private final ExchangeRateService exchangeRateService;
    private final HoldingRepository holdingRepository;
    private final PriceRepository priceRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyValuationRepository propertyValuationRepository;

    public CombinedPortfolioHistoryService(AccountRepository accountRepository,
                                            ExchangeRateService exchangeRateService,
                                            HoldingRepository holdingRepository,
                                            PriceRepository priceRepository,
                                            PropertyRepository propertyRepository,
                                            PropertyValuationRepository propertyValuationRepository) {
        this.accountRepository = accountRepository;
        this.exchangeRateService = exchangeRateService;
        this.holdingRepository = holdingRepository;
        this.priceRepository = priceRepository;
        this.propertyRepository = propertyRepository;
        this.propertyValuationRepository = propertyValuationRepository;
    }

    @Timed("wealthview.dashboard.portfolio.history")
    @Transactional(readOnly = true)
    public CombinedPortfolioHistoryResponse computeHistory(UUID tenantId, int years) {
        var clampedYears = Math.max(MIN_YEARS, Math.min(MAX_YEARS, years));
        var endDate = LocalDate.now();
        var startDate = endDate.minusYears(clampedYears);

        // Load all accounts and filter out bank accounts
        var allAccounts = accountRepository.findByTenant_Id(tenantId, Pageable.unpaged()).getContent();
        var investmentAccounts = allAccounts.stream()
                .filter(a -> !"bank".equals(a.getType()))
                .toList();

        // Load holdings and group by account
        var allHoldings = holdingRepository.findByTenant_Id(tenantId).stream()
                .filter(h -> h.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        // Load properties and valuations
        var properties = propertyRepository.findByTenant_Id(tenantId);
        var allValuations = propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(tenantId);

        if (investmentAccounts.isEmpty() && properties.isEmpty()) {
            return new CombinedPortfolioHistoryResponse(List.of(), 0, 0, 0);
        }

        var dataPointDates = generateFridays(startDate, endDate);
        // Always include today so the chart extends to the current date
        if (dataPointDates.isEmpty() || !dataPointDates.get(dataPointDates.size() - 1).equals(endDate)) {
            dataPointDates.add(endDate);
        }
        if (dataPointDates.isEmpty()) {
            return new CombinedPortfolioHistoryResponse(List.of(), 0,
                    investmentAccounts.size(), properties.size());
        }

        // Build investment data structures
        var investmentAccountIds = investmentAccounts.stream()
                .map(AccountEntity::getId)
                .collect(Collectors.toSet());

        var investmentHoldings = allHoldings.stream()
                .filter(h -> investmentAccountIds.contains(h.getAccountId()))
                .toList();

        var moneyMarketHoldings = investmentHoldings.stream()
                .filter(HoldingEntity::isMoneyMarket)
                .toList();
        var regularHoldings = investmentHoldings.stream()
                .filter(h -> !h.isMoneyMarket())
                .toList();

        var regularSymbols = regularHoldings.stream()
                .map(HoldingEntity::getSymbol)
                .distinct()
                .sorted()
                .toList();

        // Group holdings by account currency
        var currencyByAccountId = investmentAccounts.stream()
                .collect(Collectors.toMap(AccountEntity::getId, AccountEntity::getCurrency));

        var holdingsByCurrency = new HashMap<String, List<HoldingEntity>>();
        for (var h : regularHoldings) {
            var currency = currencyByAccountId.getOrDefault(h.getAccountId(), "USD");
            holdingsByCurrency.computeIfAbsent(currency, k -> new ArrayList<>()).add(h);
        }

        var mmTotalByCurrency = new HashMap<String, BigDecimal>();
        for (var h : moneyMarketHoldings) {
            var currency = currencyByAccountId.getOrDefault(h.getAccountId(), "USD");
            mmTotalByCurrency.merge(currency, h.getQuantity(), BigDecimal::add);
        }

        // Build price map
        Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap;
        if (!regularSymbols.isEmpty()) {
            var prices = priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                    regularSymbols, startDate, endDate);
            priceMap = buildPriceMap(prices);
        } else {
            priceMap = new HashMap<>();
        }

        // Build property valuation maps
        var valuationsByProperty = allValuations.stream()
                .collect(Collectors.groupingBy(PropertyValuationEntity::getPropertyId));

        // Compute data points (weekly Fridays + today)
        var dataPoints = new ArrayList<CombinedPortfolioDataPointDto>();
        for (var friday : dataPointDates) {
            var investmentValue = BigDecimal.ZERO;

            for (var entry : holdingsByCurrency.entrySet()) {
                var currency = entry.getKey();
                var holdings = entry.getValue();
                var qtyBySymbol = new HashMap<String, BigDecimal>();
                for (var h : holdings) {
                    qtyBySymbol.merge(h.getSymbol(), h.getQuantity(), BigDecimal::add);
                }
                var symbols = qtyBySymbol.keySet().stream()
                        .filter(priceMap::containsKey)
                        .sorted().toList();
                var currencyValue = computeInvestmentValue(
                        friday, symbols, qtyBySymbol, priceMap, BigDecimal.ZERO);
                investmentValue = investmentValue.add(
                        exchangeRateService.convertToUsd(currencyValue, currency, tenantId));
            }

            for (var mmEntry : mmTotalByCurrency.entrySet()) {
                investmentValue = investmentValue.add(
                        exchangeRateService.convertToUsd(mmEntry.getValue(), mmEntry.getKey(), tenantId));
            }

            var propertyEquity = computePropertyEquity(friday, properties, valuationsByProperty);
            var totalValue = investmentValue.add(propertyEquity);
            dataPoints.add(new CombinedPortfolioDataPointDto(friday, totalValue,
                    investmentValue, propertyEquity));
        }

        log.info("Computed {} combined portfolio data points for tenant {} ({} investment accounts, {} properties)",
                dataPoints.size(), tenantId, investmentAccounts.size(), properties.size());

        return new CombinedPortfolioHistoryResponse(dataPoints, dataPoints.size(),
                investmentAccounts.size(), properties.size());
    }

    private BigDecimal computeInvestmentValue(LocalDate date, List<String> pricedSymbols,
                                               Map<String, BigDecimal> quantityBySymbol,
                                               Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap,
                                               BigDecimal moneyMarketTotal) {
        var value = moneyMarketTotal;
        for (var symbol : pricedSymbols) {
            var symbolPrices = priceMap.get(symbol);
            if (symbolPrices == null) {
                continue;
            }
            var entry = symbolPrices.floorEntry(date);
            if (entry == null) {
                continue;
            }
            var quantity = quantityBySymbol.get(symbol);
            value = value.add(quantity.multiply(entry.getValue()));
        }
        return value;
    }

    private BigDecimal computePropertyEquity(LocalDate date, List<PropertyEntity> properties,
                                              Map<UUID, List<PropertyValuationEntity>> valuationsByProperty) {
        var totalEquity = BigDecimal.ZERO;
        for (var property : properties) {
            if (date.isBefore(property.getPurchaseDate())) {
                continue;
            }
            var valuations = valuationsByProperty.getOrDefault(property.getId(), List.of());
            var propertyValue = interpolatePropertyValue(date, property, valuations);
            var mortgageBalance = computeMortgageBalance(date, property);
            var equity = propertyValue.subtract(mortgageBalance);
            totalEquity = totalEquity.add(equity);
        }
        return totalEquity;
    }

    BigDecimal interpolatePropertyValue(LocalDate targetDate, PropertyEntity property,
                                         List<PropertyValuationEntity> valuations) {
        var points = new TreeMap<LocalDate, BigDecimal>();
        points.put(property.getPurchaseDate(), property.getPurchasePrice());
        for (var v : valuations) {
            points.put(v.getValuationDate(), v.getValue());
        }
        points.put(LocalDate.now(), property.getCurrentValue());

        var floor = points.floorEntry(targetDate);
        var ceiling = points.ceilingEntry(targetDate);

        if (floor == null && ceiling == null) {
            return property.getCurrentValue();
        }
        if (floor == null) {
            return ceiling.getValue();
        }
        if (ceiling == null || floor.getKey().equals(ceiling.getKey())) {
            return floor.getValue();
        }

        // Linear interpolation between floor and ceiling
        long totalDays = ChronoUnit.DAYS.between(floor.getKey(), ceiling.getKey());
        long elapsedDays = ChronoUnit.DAYS.between(floor.getKey(), targetDate);

        if (totalDays == 0) {
            return floor.getValue();
        }

        var ratio = new BigDecimal(elapsedDays)
                .divide(new BigDecimal(totalDays), MathContext.DECIMAL128);
        var diff = ceiling.getValue().subtract(floor.getValue());
        return floor.getValue().add(diff.multiply(ratio))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeMortgageBalance(LocalDate date, PropertyEntity property) {
        if (property.hasLoanDetails()) {
            return AmortizationCalculator.remainingBalance(
                    property.getLoanAmount(),
                    property.getAnnualInterestRate(),
                    property.getLoanTermMonths(),
                    property.getLoanStartDate(),
                    date);
        }
        return property.getMortgageBalance();
    }

    private Map<String, NavigableMap<LocalDate, BigDecimal>> buildPriceMap(List<PriceEntity> prices) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap = new HashMap<>();
        for (var price : prices) {
            priceMap.computeIfAbsent(price.getSymbol(), k -> new TreeMap<>())
                    .put(price.getDate(), price.getClosePrice());
        }
        return priceMap;
    }

    private List<LocalDate> generateFridays(LocalDate start, LocalDate end) {
        var fridays = new ArrayList<LocalDate>();
        var friday = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        while (!friday.isAfter(end)) {
            fridays.add(friday);
            friday = friday.plusWeeks(1);
        }
        return fridays;
    }
}
