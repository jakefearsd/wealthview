package com.wealthview.core.portfolio;

import com.wealthview.core.common.Entities;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.portfolio.dto.PortfolioDataPointDto;
import com.wealthview.core.portfolio.dto.PortfolioHistoryResponse;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TheoreticalPortfolioService {

    private static final Logger log = LoggerFactory.getLogger(TheoreticalPortfolioService.class);
    private static final int MIN_MONTHS = 6;
    private static final int MAX_MONTHS = 240;

    private final AccountRepository accountRepository;
    private final ExchangeRateService exchangeRateService;
    private final HoldingRepository holdingRepository;
    private final PriceRepository priceRepository;

    public TheoreticalPortfolioService(AccountRepository accountRepository,
                                       ExchangeRateService exchangeRateService,
                                       HoldingRepository holdingRepository,
                                       PriceRepository priceRepository) {
        this.accountRepository = accountRepository;
        this.exchangeRateService = exchangeRateService;
        this.holdingRepository = holdingRepository;
        this.priceRepository = priceRepository;
    }

    @Transactional(readOnly = true)
    public PortfolioHistoryResponse computeHistory(UUID tenantId, UUID accountId, int months) {
        var clampedMonths = Math.max(MIN_MONTHS, Math.min(MAX_MONTHS, months));
        var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(Entities.notFound("Account"));

        if ("bank".equals(account.getType())) {
            return emptyResponse(accountId);
        }

        var holdings = holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId)
                .stream()
                .filter(h -> h.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (holdings.isEmpty()) {
            return emptyResponse(accountId);
        }

        var moneyMarketHoldings = holdings.stream().filter(HoldingEntity::isMoneyMarket).toList();
        var regularSymbols = holdings.stream()
                .filter(h -> !h.isMoneyMarket())
                .map(HoldingEntity::getSymbol)
                .distinct().sorted().toList();

        var moneyMarketTotal = moneyMarketHoldings.stream()
                .map(h -> h.getQuantity().multiply(BigDecimal.ONE))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var quantityBySymbol = holdings.stream()
                .collect(Collectors.toMap(HoldingEntity::getSymbol, HoldingEntity::getQuantity));

        var endDate = LocalDate.now();
        var startDate = endDate.minusMonths(clampedMonths);

        var resolved = resolvePricedSymbols(regularSymbols, startDate, endDate, accountId);
        var priceMap = resolved.priceMap();
        var pricedSymbols = resolved.pricedSymbols();

        if (pricedSymbols.isEmpty() && moneyMarketHoldings.isEmpty()) {
            log.info("No price data available for any holdings of account {}", accountId);
            return emptyResponse(accountId);
        }

        var dataPointDates = generateFridays(startDate, endDate);
        // Always include today so the chart extends to the current date
        if (dataPointDates.isEmpty() || !dataPointDates.get(dataPointDates.size() - 1).equals(endDate)) {
            dataPointDates.add(endDate);
        }
        var hasMoneyMarket = !moneyMarketHoldings.isEmpty();
        var dataPoints = computeWeeklyValuesWithMoneyMarket(
                dataPointDates, pricedSymbols, quantityBySymbol, priceMap, moneyMarketTotal);

        var currency = account.getCurrency();
        if (!"USD".equals(currency)) {
            var convertedPoints = new ArrayList<PortfolioDataPointDto>();
            for (var dp : dataPoints) {
                var convertedValue = exchangeRateService.convertToUsd(dp.totalValue(), currency, tenantId);
                convertedPoints.add(new PortfolioDataPointDto(dp.date(), convertedValue));
            }
            dataPoints = convertedPoints;
            if (hasMoneyMarket) {
                moneyMarketTotal = exchangeRateService.convertToUsd(moneyMarketTotal, currency, tenantId);
            }
        }

        var allSymbols = buildCombinedSymbolList(pricedSymbols, moneyMarketHoldings);

        log.info("Computed {} weekly data points for account {} with {} symbols",
                dataPoints.size(), accountId, allSymbols.size());

        return new PortfolioHistoryResponse(accountId, dataPoints, allSymbols, dataPoints.size(),
                hasMoneyMarket, hasMoneyMarket ? moneyMarketTotal : null);
    }

    private static PortfolioHistoryResponse emptyResponse(UUID accountId) {
        return new PortfolioHistoryResponse(accountId, List.of(), List.of(), 0, false, null);
    }

    private record ResolvedPrices(
            Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap,
            List<String> pricedSymbols) {
    }

    private ResolvedPrices resolvePricedSymbols(List<String> regularSymbols,
                                                 LocalDate startDate, LocalDate endDate,
                                                 UUID accountId) {
        if (regularSymbols.isEmpty()) {
            return new ResolvedPrices(new HashMap<>(), List.of());
        }
        var prices = priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                regularSymbols, startDate, endDate);
        var priceMap = buildPriceMap(prices);
        var pricedSymbols = regularSymbols.stream().filter(priceMap::containsKey).toList();

        if (pricedSymbols.size() < regularSymbols.size()) {
            var missing = regularSymbols.stream().filter(s -> !priceMap.containsKey(s)).toList();
            log.info("Skipping symbols without price data for account {}: {}", accountId, missing);
        }
        return new ResolvedPrices(priceMap, pricedSymbols);
    }

    private List<String> buildCombinedSymbolList(List<String> pricedSymbols,
                                                  List<HoldingEntity> moneyMarketHoldings) {
        var allSymbols = new ArrayList<>(pricedSymbols);
        for (var mmh : moneyMarketHoldings) {
            if (!allSymbols.contains(mmh.getSymbol())) {
                allSymbols.add(mmh.getSymbol());
            }
        }
        allSymbols.sort(String::compareTo);
        return allSymbols;
    }

    private Map<String, NavigableMap<LocalDate, BigDecimal>> buildPriceMap(List<PriceEntity> prices) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap = new HashMap<>();
        for (var price : prices) {
            priceMap.computeIfAbsent(price.getSymbol(), k -> new TreeMap<>())
                    .put(price.getDate(), price.getClosePrice());
        }
        return priceMap;
    }

    private List<PortfolioDataPointDto> computeWeeklyValuesWithMoneyMarket(
            List<LocalDate> dates,
            List<String> pricedSymbols,
            Map<String, BigDecimal> quantityBySymbol,
            Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap,
            BigDecimal moneyMarketTotal) {
        var dataPoints = new ArrayList<PortfolioDataPointDto>();
        for (var date : dates) {
            var pricedValue = lookupTotalValue(date, pricedSymbols, quantityBySymbol, priceMap);
            if (pricedValue.isPresent() || moneyMarketTotal.compareTo(BigDecimal.ZERO) > 0) {
                var total = pricedValue.orElse(BigDecimal.ZERO).add(moneyMarketTotal);
                dataPoints.add(new PortfolioDataPointDto(date, total));
            }
        }
        return dataPoints;
    }

    private Optional<BigDecimal> lookupTotalValue(
            LocalDate date,
            List<String> symbols,
            Map<String, BigDecimal> quantityBySymbol,
            Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap) {
        if (symbols.isEmpty()) {
            return Optional.empty();
        }
        var totalValue = BigDecimal.ZERO;
        for (var symbol : symbols) {
            var symbolPrices = priceMap.get(symbol);
            if (symbolPrices == null) {
                return Optional.empty();
            }
            var entry = symbolPrices.floorEntry(date);
            if (entry == null) {
                return Optional.empty();
            }
            var quantity = quantityBySymbol.get(symbol);
            totalValue = totalValue.add(quantity.multiply(entry.getValue()));
        }
        return Optional.of(totalValue);
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
