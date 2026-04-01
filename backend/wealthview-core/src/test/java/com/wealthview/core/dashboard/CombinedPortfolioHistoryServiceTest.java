package com.wealthview.core.dashboard;

import com.wealthview.core.dashboard.dto.CombinedPortfolioDataPointDto;
import com.wealthview.core.dashboard.dto.CombinedPortfolioHistoryResponse;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyValuationEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.PropertyValuationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CombinedPortfolioHistoryServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    @Mock private AccountRepository accountRepository;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private HoldingRepository holdingRepository;
    @Mock private PriceRepository priceRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private PropertyValuationRepository propertyValuationRepository;

    private CombinedPortfolioHistoryService service;

    @BeforeEach
    void setUp() {
        lenient().when(exchangeRateService.convertToUsd(any(BigDecimal.class), eq("USD"), any(UUID.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service = new CombinedPortfolioHistoryService(
                accountRepository, exchangeRateService, holdingRepository, priceRepository,
                propertyRepository, propertyValuationRepository);
    }

    @Test
    void computeHistory_noAccountsNoProperties_returnsEmptyDataPoints() {
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of()));
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        var result = service.computeHistory(TENANT_ID, 2);

        assertThat(result.dataPoints()).isEmpty();
        assertThat(result.investmentAccountCount()).isZero();
        assertThat(result.propertyCount()).isZero();
        assertThat(result.weeks()).isZero();
    }

    @Test
    void computeHistory_singleBrokerageAccount_returnsWeeklyInvestmentValues() {
        var account = mockAccount("brokerage");
        var accountId = account.getId();
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(account)));

        var holding = mockHolding(accountId, "VTI", "10", false);
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(holding));

        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        // Provide price data for every day in the range
        var endDate = LocalDate.now();
        var startDate = endDate.minusYears(1);
        var prices = buildPrices("VTI", startDate, endDate, new BigDecimal("200"));
        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("VTI")), any(), any())).thenReturn(prices);

        var result = service.computeHistory(TENANT_ID, 1);

        assertThat(result.investmentAccountCount()).isEqualTo(1);
        assertThat(result.propertyCount()).isZero();
        assertThat(result.dataPoints()).isNotEmpty();

        // Each data point should have investment value = 10 * 200 = 2000, property equity = 0
        for (var dp : result.dataPoints()) {
            assertThat(dp.investmentValue()).isEqualByComparingTo(new BigDecimal("2000"));
            assertThat(dp.propertyEquity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dp.totalValue()).isEqualByComparingTo(dp.investmentValue());
        }

        // All points except the last should be Fridays; the last should be today
        var lastPoint = result.dataPoints().get(result.dataPoints().size() - 1);
        assertThat(lastPoint.date()).isEqualTo(LocalDate.now());
        var allButLast = result.dataPoints().subList(0, result.dataPoints().size() - 1);
        for (var dp : allButLast) {
            assertThat(dp.date().getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        }
    }

    @Test
    void computeHistory_singlePropertyNoMortgage_returnsWeeklyPropertyEquity() {
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of()));
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        var property = mockProperty(
                LocalDate.now().minusYears(3), new BigDecimal("300000"),
                new BigDecimal("350000"), BigDecimal.ZERO, false);
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        var result = service.computeHistory(TENANT_ID, 1);

        assertThat(result.propertyCount()).isEqualTo(1);
        assertThat(result.dataPoints()).isNotEmpty();

        // With no valuations, interpolation between purchasePrice and currentValue
        // All points should have propertyEquity > 0 and investmentValue = 0
        for (var dp : result.dataPoints()) {
            assertThat(dp.investmentValue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dp.propertyEquity()).isGreaterThan(BigDecimal.ZERO);
            assertThat(dp.totalValue()).isEqualByComparingTo(dp.propertyEquity());
        }
    }

    @Test
    void computeHistory_singlePropertyWithMortgage_returnsEquityMinusMortgage() {
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of()));
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        var purchaseDate = LocalDate.now().minusYears(3);
        var property = mockPropertyWithLoan(
                purchaseDate, new BigDecimal("300000"),
                new BigDecimal("350000"),
                new BigDecimal("240000"), new BigDecimal("6"), 360, purchaseDate);

        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        var result = service.computeHistory(TENANT_ID, 1);

        assertThat(result.dataPoints()).isNotEmpty();

        // With mortgage, equity should be lower than without mortgage
        for (var dp : result.dataPoints()) {
            assertThat(dp.propertyEquity()).isLessThan(new BigDecimal("350000"));
            assertThat(dp.totalValue()).isEqualByComparingTo(dp.propertyEquity());
        }
    }

    @Test
    void computeHistory_propertyBeforePurchaseDate_contributesZero() {
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of()));
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        // Property purchased only 3 months ago
        var purchaseDate = LocalDate.now().minusMonths(3);
        var property = mockProperty(
                purchaseDate, new BigDecimal("300000"),
                new BigDecimal("310000"), BigDecimal.ZERO, false);
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        // Look back 1 year — so many Fridays will be before purchase
        var result = service.computeHistory(TENANT_ID, 1);

        assertThat(result.dataPoints()).isNotEmpty();

        // Points before purchase should have zero property equity
        var beforePurchase = result.dataPoints().stream()
                .filter(dp -> dp.date().isBefore(purchaseDate))
                .toList();
        assertThat(beforePurchase).isNotEmpty();
        for (var dp : beforePurchase) {
            assertThat(dp.propertyEquity()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        // Points after purchase should have non-zero property equity
        var afterPurchase = result.dataPoints().stream()
                .filter(dp -> !dp.date().isBefore(purchaseDate))
                .toList();
        assertThat(afterPurchase).isNotEmpty();
        for (var dp : afterPurchase) {
            assertThat(dp.propertyEquity()).isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Test
    void computeHistory_combinedBrokerageAndProperty_sumsBoth() {
        var account = mockAccount("brokerage");
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(account)));

        var holding = mockHolding(account.getId(), "VOO", "5", false);
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(holding));

        var prices = buildPrices("VOO", LocalDate.now().minusYears(1), LocalDate.now(),
                new BigDecimal("400"));
        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("VOO")), any(), any())).thenReturn(prices);

        var property = mockProperty(
                LocalDate.now().minusYears(3), new BigDecimal("300000"),
                new BigDecimal("350000"), BigDecimal.ZERO, false);
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        var result = service.computeHistory(TENANT_ID, 1);

        assertThat(result.investmentAccountCount()).isEqualTo(1);
        assertThat(result.propertyCount()).isEqualTo(1);
        assertThat(result.dataPoints()).isNotEmpty();

        for (var dp : result.dataPoints()) {
            assertThat(dp.investmentValue()).isGreaterThan(BigDecimal.ZERO);
            assertThat(dp.propertyEquity()).isGreaterThan(BigDecimal.ZERO);
            assertThat(dp.totalValue()).isEqualByComparingTo(
                    dp.investmentValue().add(dp.propertyEquity()));
        }
    }

    @Test
    void computeHistory_moneyMarketHoldings_includedAtConstantValue() {
        var account = mockAccount("brokerage");
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(account)));

        var mmHolding = mockHolding(account.getId(), "SPAXX", "5000", true);
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(mmHolding));

        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        var result = service.computeHistory(TENANT_ID, 1);

        assertThat(result.dataPoints()).isNotEmpty();
        for (var dp : result.dataPoints()) {
            // 5000 shares * $1.00 = $5000
            assertThat(dp.investmentValue()).isEqualByComparingTo(new BigDecimal("5000"));
        }
    }

    @Test
    void computeHistory_bankAccountsExcluded() {
        var bankAccount = mockAccount("bank");
        var brokerageAccount = mockAccount("brokerage");
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(bankAccount, brokerageAccount)));

        var holding = mockHolding(brokerageAccount.getId(), "VTI", "10", false);
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(holding));

        var prices = buildPrices("VTI", LocalDate.now().minusYears(1), LocalDate.now(),
                new BigDecimal("200"));
        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("VTI")), any(), any())).thenReturn(prices);

        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        var result = service.computeHistory(TENANT_ID, 1);

        // Only brokerage account should be counted
        assertThat(result.investmentAccountCount()).isEqualTo(1);
        for (var dp : result.dataPoints()) {
            // Only VTI: 10 * 200 = 2000. Bank balance should not appear.
            assertThat(dp.investmentValue()).isEqualByComparingTo(new BigDecimal("2000"));
        }
    }

    @Test
    void computeHistory_multipleProperties_sumsEquity() {
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of()));
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        var property1 = mockProperty(
                LocalDate.now().minusYears(3), new BigDecimal("200000"),
                new BigDecimal("220000"), BigDecimal.ZERO, false);
        var property2 = mockProperty(
                LocalDate.now().minusYears(3), new BigDecimal("300000"),
                new BigDecimal("330000"), BigDecimal.ZERO, false);
        when(propertyRepository.findByTenant_Id(TENANT_ID))
                .thenReturn(List.of(property1, property2));
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of());

        var result = service.computeHistory(TENANT_ID, 1);

        assertThat(result.propertyCount()).isEqualTo(2);
        assertThat(result.dataPoints()).isNotEmpty();

        // Each data point's property equity should be sum of both properties' equity
        for (var dp : result.dataPoints()) {
            assertThat(dp.propertyEquity()).isGreaterThan(BigDecimal.ZERO);
        }

        // The last data point should be close to (220000 + 330000) = 550000 (no mortgage)
        var lastPoint = result.dataPoints().get(result.dataPoints().size() - 1);
        assertThat(lastPoint.propertyEquity()).isGreaterThan(new BigDecimal("500000"));
    }

    @Test
    void computeHistory_propertyWithValuation_groupsByPropertyIdField() {
        when(accountRepository.findByTenant_Id(TENANT_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of()));
        when(holdingRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        var property = mockProperty(
                LocalDate.now().minusYears(3), new BigDecimal("300000"),
                new BigDecimal("350000"), BigDecimal.ZERO, false);
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));

        var valuation = mockValuation(property.getId(),
                LocalDate.now().minusMonths(6), new BigDecimal("325000"));
        when(propertyValuationRepository.findByTenant_IdOrderByValuationDateAsc(TENANT_ID))
                .thenReturn(List.of(valuation));

        var result = service.computeHistory(TENANT_ID, 1);

        assertThat(result.dataPoints()).isNotEmpty();
        assertThat(result.propertyCount()).isEqualTo(1);
    }

    // --- Helper Methods ---

    private AccountEntity mockAccount(String type) {
        var account = mock(AccountEntity.class);
        var id = UUID.randomUUID();
        lenient().when(account.getId()).thenReturn(id);
        lenient().when(account.getType()).thenReturn(type);
        lenient().when(account.getName()).thenReturn(type + " account");
        lenient().when(account.getCurrency()).thenReturn("USD");
        return account;
    }

    private HoldingEntity mockHolding(UUID accountId, String symbol, String quantity, boolean moneyMarket) {
        var holding = mock(HoldingEntity.class);
        lenient().when(holding.getAccountId()).thenReturn(accountId);
        lenient().when(holding.getSymbol()).thenReturn(symbol);
        lenient().when(holding.getQuantity()).thenReturn(new BigDecimal(quantity));
        lenient().when(holding.isMoneyMarket()).thenReturn(moneyMarket);
        return holding;
    }

    private PropertyEntity mockProperty(LocalDate purchaseDate, BigDecimal purchasePrice,
                                         BigDecimal currentValue, BigDecimal mortgageBalance,
                                         boolean hasLoanDetails) {
        var property = mock(PropertyEntity.class);
        var id = UUID.randomUUID();
        lenient().when(property.getId()).thenReturn(id);
        lenient().when(property.getPurchaseDate()).thenReturn(purchaseDate);
        lenient().when(property.getPurchasePrice()).thenReturn(purchasePrice);
        lenient().when(property.getCurrentValue()).thenReturn(currentValue);
        lenient().when(property.getMortgageBalance()).thenReturn(mortgageBalance);
        lenient().when(property.hasLoanDetails()).thenReturn(hasLoanDetails);
        return property;
    }

    private PropertyEntity mockPropertyWithLoan(LocalDate purchaseDate, BigDecimal purchasePrice,
                                                 BigDecimal currentValue,
                                                 BigDecimal loanAmount, BigDecimal annualRate,
                                                 int termMonths, LocalDate loanStartDate) {
        var property = mockProperty(purchaseDate, purchasePrice, currentValue, BigDecimal.ZERO, true);
        lenient().when(property.getLoanAmount()).thenReturn(loanAmount);
        lenient().when(property.getAnnualInterestRate()).thenReturn(annualRate);
        lenient().when(property.getLoanTermMonths()).thenReturn(termMonths);
        lenient().when(property.getLoanStartDate()).thenReturn(loanStartDate);
        return property;
    }

    private PropertyValuationEntity mockValuation(UUID propertyId, LocalDate date, BigDecimal value) {
        var valuation = mock(PropertyValuationEntity.class);
        when(valuation.getPropertyId()).thenReturn(propertyId);
        when(valuation.getValuationDate()).thenReturn(date);
        when(valuation.getValue()).thenReturn(value);
        return valuation;
    }

    private List<PriceEntity> buildPrices(String symbol, LocalDate start, LocalDate end, BigDecimal price) {
        var prices = new java.util.ArrayList<PriceEntity>();
        // Weekly prices on Fridays
        var friday = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        while (!friday.isAfter(end)) {
            var entity = mock(PriceEntity.class);
            lenient().when(entity.getSymbol()).thenReturn(symbol);
            lenient().when(entity.getDate()).thenReturn(friday);
            lenient().when(entity.getClosePrice()).thenReturn(price);
            prices.add(entity);
            friday = friday.plusWeeks(1);
        }
        // Also add a price for 'end' (today) so the final data point can find a price
        if (prices.isEmpty() || !prices.get(prices.size() - 1).getDate().equals(end)) {
            var entity = mock(PriceEntity.class);
            lenient().when(entity.getSymbol()).thenReturn(symbol);
            lenient().when(entity.getDate()).thenReturn(end);
            lenient().when(entity.getClosePrice()).thenReturn(price);
            prices.add(entity);
        }
        return prices;
    }
}
