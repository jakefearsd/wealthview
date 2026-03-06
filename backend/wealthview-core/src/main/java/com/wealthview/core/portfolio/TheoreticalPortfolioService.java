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
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TheoreticalPortfolioService {

    private static final Logger log = LoggerFactory.getLogger(TheoreticalPortfolioService.class);
    private static final int HISTORY_YEARS = 2;

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
    public PortfolioHistoryResponse computeHistory(UUID tenantId, UUID accountId) {
        var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        if ("bank".equals(account.getType())) {
            return new PortfolioHistoryResponse(accountId, List.of(), List.of(), 0);
        }

        var holdings = holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId)
                .stream()
                .filter(h -> h.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (holdings.isEmpty()) {
            return new PortfolioHistoryResponse(accountId, List.of(), List.of(), 0);
        }

        var symbols = holdings.stream()
                .map(HoldingEntity::getSymbol)
                .distinct()
                .sorted()
                .toList();

        var quantityBySymbol = holdings.stream()
                .collect(Collectors.toMap(HoldingEntity::getSymbol, HoldingEntity::getQuantity));

        var endDate = LocalDate.now();
        var startDate = endDate.minusYears(HISTORY_YEARS);

        var prices = priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                symbols, startDate, endDate);

        var priceMap = buildPriceMap(prices);
        var fridays = generateFridays(startDate, endDate);
        var dataPoints = computeWeeklyValues(fridays, symbols, quantityBySymbol, priceMap);

        log.info("Computed {} weekly data points for account {} with {} symbols",
                dataPoints.size(), accountId, symbols.size());

        return new PortfolioHistoryResponse(accountId, dataPoints, symbols, dataPoints.size());
    }

    private Map<String, TreeMap<LocalDate, BigDecimal>> buildPriceMap(List<PriceEntity> prices) {
        Map<String, TreeMap<LocalDate, BigDecimal>> priceMap = new HashMap<>();
        for (var price : prices) {
            priceMap.computeIfAbsent(price.getSymbol(), k -> new TreeMap<>())
                    .put(price.getDate(), price.getClosePrice());
        }
        return priceMap;
    }

    private List<PortfolioDataPointDto> computeWeeklyValues(
            List<LocalDate> fridays,
            List<String> symbols,
            Map<String, BigDecimal> quantityBySymbol,
            Map<String, TreeMap<LocalDate, BigDecimal>> priceMap) {
        var dataPoints = new ArrayList<PortfolioDataPointDto>();
        for (var friday : fridays) {
            lookupTotalValue(friday, symbols, quantityBySymbol, priceMap)
                    .ifPresent(value -> dataPoints.add(new PortfolioDataPointDto(friday, value)));
        }
        return dataPoints;
    }

    private Optional<BigDecimal> lookupTotalValue(
            LocalDate date,
            List<String> symbols,
            Map<String, BigDecimal> quantityBySymbol,
            Map<String, TreeMap<LocalDate, BigDecimal>> priceMap) {
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
