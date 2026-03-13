package com.wealthview.core.portfolio;

import com.wealthview.core.exception.EntityNotFoundException;
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
    private static final int MIN_YEARS = 1;
    private static final int MAX_YEARS = 10;

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final PriceRepository priceRepository;

    public TheoreticalPortfolioService(AccountRepository accountRepository,
                                       HoldingRepository holdingRepository,
                                       PriceRepository priceRepository) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.priceRepository = priceRepository;
    }

    @Transactional(readOnly = true)
    public PortfolioHistoryResponse computeHistory(UUID tenantId, UUID accountId, int years) {
        var clampedYears = Math.max(MIN_YEARS, Math.min(MAX_YEARS, years));
        var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        if ("bank".equals(account.getType())) {
            return new PortfolioHistoryResponse(accountId, List.of(), List.of(), 0, false, null);
        }

        var holdings = holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId)
                .stream()
                .filter(h -> h.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (holdings.isEmpty()) {
            return new PortfolioHistoryResponse(accountId, List.of(), List.of(), 0, false, null);
        }

        // Partition into money market and priced holdings
        var moneyMarketHoldings = holdings.stream()
                .filter(HoldingEntity::isMoneyMarket)
                .toList();
        var regularHoldings = holdings.stream()
                .filter(h -> !h.isMoneyMarket())
                .toList();

        var moneyMarketTotal = moneyMarketHoldings.stream()
                .map(h -> h.getQuantity().multiply(BigDecimal.ONE))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var regularSymbols = regularHoldings.stream()
                .map(HoldingEntity::getSymbol)
                .distinct()
                .sorted()
                .toList();

        var quantityBySymbol = holdings.stream()
                .collect(Collectors.toMap(HoldingEntity::getSymbol, HoldingEntity::getQuantity));

        var endDate = LocalDate.now();
        var startDate = endDate.minusYears(clampedYears);

        // Build price map for regular symbols
        final Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap;
        final List<String> pricedSymbols;
        if (!regularSymbols.isEmpty()) {
            var prices = priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                    regularSymbols, startDate, endDate);
            priceMap = buildPriceMap(prices);

            pricedSymbols = regularSymbols.stream()
                    .filter(priceMap::containsKey)
                    .toList();

            if (pricedSymbols.size() < regularSymbols.size()) {
                var missing = regularSymbols.stream()
                        .filter(s -> !priceMap.containsKey(s))
                        .toList();
                log.info("Skipping symbols without price data for account {}: {}", accountId, missing);
            }
        } else {
            priceMap = new HashMap<>();
            pricedSymbols = List.of();
        }

        if (pricedSymbols.isEmpty() && moneyMarketHoldings.isEmpty()) {
            log.info("No price data available for any holdings of account {}", accountId);
            return new PortfolioHistoryResponse(accountId, List.of(), List.of(), 0, false, null);
        }

        var fridays = generateFridays(startDate, endDate);

        // Compute weekly values for priced symbols
        var hasMoneyMarket = !moneyMarketHoldings.isEmpty();
        var dataPoints = computeWeeklyValuesWithMoneyMarket(
                fridays, pricedSymbols, quantityBySymbol, priceMap, moneyMarketTotal);

        // Build combined symbol list
        var allSymbols = new ArrayList<>(pricedSymbols);
        for (var mmh : moneyMarketHoldings) {
            if (!allSymbols.contains(mmh.getSymbol())) {
                allSymbols.add(mmh.getSymbol());
            }
        }
        allSymbols.sort(String::compareTo);

        log.info("Computed {} weekly data points for account {} with {} symbols",
                dataPoints.size(), accountId, allSymbols.size());

        return new PortfolioHistoryResponse(accountId, dataPoints, allSymbols, dataPoints.size(),
                hasMoneyMarket, hasMoneyMarket ? moneyMarketTotal : null);
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
            List<LocalDate> fridays,
            List<String> pricedSymbols,
            Map<String, BigDecimal> quantityBySymbol,
            Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap,
            BigDecimal moneyMarketTotal) {
        var dataPoints = new ArrayList<PortfolioDataPointDto>();
        for (var friday : fridays) {
            var pricedValue = lookupTotalValue(friday, pricedSymbols, quantityBySymbol, priceMap);
            if (pricedValue.isPresent() || moneyMarketTotal.compareTo(BigDecimal.ZERO) > 0) {
                var total = pricedValue.orElse(BigDecimal.ZERO).add(moneyMarketTotal);
                dataPoints.add(new PortfolioDataPointDto(friday, total));
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
