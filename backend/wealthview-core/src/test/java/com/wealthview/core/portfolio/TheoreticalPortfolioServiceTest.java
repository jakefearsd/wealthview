package com.wealthview.core.portfolio;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.portfolio.dto.PortfolioDataPointDto;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TheoreticalPortfolioServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ExchangeRateService exchangeRateService;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private PriceRepository priceRepository;

    @InjectMocks
    private TheoreticalPortfolioService service;

    private UUID tenantId;
    private UUID accountId;
    private TenantEntity tenant;
    private AccountEntity brokerageAccount;
    private AccountEntity bankAccount;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        brokerageAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        bankAccount = new AccountEntity(tenant, "Checking", "bank", "Chase");

        lenient().when(exchangeRateService.convertToUsd(any(BigDecimal.class), eq("USD"), any(UUID.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void computeHistory_accountNotFound_throwsEntityNotFoundException() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.computeHistory(tenantId, accountId, 24))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void computeHistory_bankAccount_returnsEmptyDataPoints() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(bankAccount));

        var result = service.computeHistory(tenantId, accountId, 24);

        assertThat(result.dataPoints()).isEmpty();
        assertThat(result.accountId()).isEqualTo(accountId);
    }

    @Test
    void computeHistory_noHoldings_returnsEmptyDataPoints() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of());

        var result = service.computeHistory(tenantId, accountId, 24);

        assertThat(result.dataPoints()).isEmpty();
        assertThat(result.symbols()).isEmpty();
    }

    @Test
    void computeHistory_customMonths_passesCorrectDateRange() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var holding = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), BigDecimal.ZERO);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(holding));

        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("AAPL")), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        service.computeHistory(tenantId, accountId, 60);

        var captor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(priceRepository).findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("AAPL")), captor.capture(), captor.capture());

        var startDate = captor.getAllValues().get(0);
        var endDate = captor.getAllValues().get(1);

        // 60-month horizon: start date should be 60 months before end date
        assertThat(startDate).isEqualTo(endDate.minusMonths(60));
    }

    @Test
    void computeHistory_sixMonths_passesCorrectDateRange() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var holding = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), BigDecimal.ZERO);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(holding));

        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("AAPL")), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        service.computeHistory(tenantId, accountId, 6);

        var captor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(priceRepository).findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("AAPL")), captor.capture(), captor.capture());

        var startDate = captor.getAllValues().get(0);
        var endDate = captor.getAllValues().get(1);

        assertThat(startDate).isEqualTo(endDate.minusMonths(6));
    }

    @Test
    void computeHistory_twentyYears_passesCorrectDateRange() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var holding = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), BigDecimal.ZERO);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(holding));

        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("AAPL")), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        service.computeHistory(tenantId, accountId, 240);

        var captor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(priceRepository).findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("AAPL")), captor.capture(), captor.capture());

        var startDate = captor.getAllValues().get(0);
        var endDate = captor.getAllValues().get(1);

        assertThat(startDate).isEqualTo(endDate.minusMonths(240));
    }

    @Test
    void computeHistory_monthsClamped_to6through240() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of());

        // Should not throw for edge values — just clamp
        var result0 = service.computeHistory(tenantId, accountId, 0);
        var result300 = service.computeHistory(tenantId, accountId, 300);

        assertThat(result0).isNotNull();
        assertThat(result300).isNotNull();
    }

    @Test
    void computeHistory_lastDataPointIsToday() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var spaxx = new HoldingEntity(brokerageAccount, tenant, "SPAXX",
                new BigDecimal("10000"), BigDecimal.ZERO);
        spaxx.setMoneyMarket(true);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(spaxx));

        var result = service.computeHistory(tenantId, accountId, 12);

        assertThat(result.dataPoints()).isNotEmpty();
        var lastPoint = result.dataPoints().get(result.dataPoints().size() - 1);
        assertThat(lastPoint.date()).isEqualTo(LocalDate.now());

        // All data points except the last should be Fridays
        var allButLast = result.dataPoints().subList(0, result.dataPoints().size() - 1);
        for (var dp : allButLast) {
            assertThat(dp.date().getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        }
    }

    @Test
    void computeHistory_singleHoldingWithPrices_computesWeeklyValues() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var holding = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), BigDecimal.ZERO);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(holding));

        // Create prices for two consecutive Fridays
        var friday1 = LocalDate.of(2025, 1, 3); // A Friday
        var friday2 = LocalDate.of(2025, 1, 10); // Next Friday
        var prices = List.of(
                new PriceEntity("AAPL", friday1, new BigDecimal("150.0000"), "seed"),
                new PriceEntity("AAPL", friday2, new BigDecimal("155.0000"), "seed")
        );
        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("AAPL")), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(prices);

        var result = service.computeHistory(tenantId, accountId, 24);

        assertThat(result.symbols()).containsExactly("AAPL");
        assertThat(result.dataPoints()).isNotEmpty();

        // Find the data points matching our price dates
        var point1 = result.dataPoints().stream()
                .filter(dp -> dp.date().equals(friday1)).findFirst();
        var point2 = result.dataPoints().stream()
                .filter(dp -> dp.date().equals(friday2)).findFirst();

        assertThat(point1).isPresent();
        assertThat(point1.get().totalValue()).isEqualByComparingTo(new BigDecimal("1500.0000"));
        assertThat(point2).isPresent();
        assertThat(point2.get().totalValue()).isEqualByComparingTo(new BigDecimal("1550.0000"));
    }

    @Test
    void computeHistory_multipleHoldings_sumsAcrossSymbols() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var holding1 = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), BigDecimal.ZERO);
        var holding2 = new HoldingEntity(brokerageAccount, tenant, "GOOG",
                new BigDecimal("5"), BigDecimal.ZERO);
        // Zero-quantity holding should be filtered out
        var holding3 = new HoldingEntity(brokerageAccount, tenant, "MSFT",
                BigDecimal.ZERO, BigDecimal.ZERO);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(holding1, holding2, holding3));

        var friday = LocalDate.of(2025, 1, 3);
        var prices = List.of(
                new PriceEntity("AAPL", friday, new BigDecimal("150.0000"), "seed"),
                new PriceEntity("GOOG", friday, new BigDecimal("100.0000"), "seed")
        );
        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(prices);

        var result = service.computeHistory(tenantId, accountId, 24);

        assertThat(result.symbols()).containsExactlyInAnyOrder("AAPL", "GOOG");

        var point = result.dataPoints().stream()
                .filter(dp -> dp.date().equals(friday)).findFirst();
        assertThat(point).isPresent();
        // 10 * 150 + 5 * 100 = 2000
        assertThat(point.get().totalValue()).isEqualByComparingTo(new BigDecimal("2000.0000"));
    }

    @Test
    void computeHistory_someSymbolsWithoutPrices_skipsMissingSymbolsAndComputesRest() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var holding1 = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), BigDecimal.ZERO);
        var holding2 = new HoldingEntity(brokerageAccount, tenant, "SPAXX",
                new BigDecimal("2500"), BigDecimal.ZERO);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(holding1, holding2));

        var friday = LocalDate.of(2025, 1, 3);
        // Only AAPL has prices, SPAXX does not
        var prices = List.of(
                new PriceEntity("AAPL", friday, new BigDecimal("150.0000"), "seed")
        );
        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(prices);

        var result = service.computeHistory(tenantId, accountId, 24);

        // Should still return data points using AAPL only
        assertThat(result.symbols()).containsExactly("AAPL");
        assertThat(result.dataPoints()).isNotEmpty();

        var point = result.dataPoints().stream()
                .filter(dp -> dp.date().equals(friday)).findFirst();
        assertThat(point).isPresent();
        assertThat(point.get().totalValue()).isEqualByComparingTo(new BigDecimal("1500.0000"));
    }

    @Test
    void computeHistory_withMoneyMarketHolding_includesAtConstantValue() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var spaxx = new HoldingEntity(brokerageAccount, tenant, "SPAXX",
                new BigDecimal("196049.86"), BigDecimal.ZERO);
        spaxx.setMoneyMarket(true);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(spaxx));

        var result = service.computeHistory(tenantId, accountId, 12);

        assertThat(result.symbols()).containsExactly("SPAXX");
        assertThat(result.dataPoints()).isNotEmpty();
        assertThat(result.hasMoneyMarketHoldings()).isTrue();
        assertThat(result.moneyMarketTotal()).isEqualByComparingTo(new BigDecimal("196049.86"));

        // All data points should have same value
        for (var dp : result.dataPoints()) {
            assertThat(dp.totalValue()).isEqualByComparingTo(new BigDecimal("196049.86"));
        }
    }

    @Test
    void computeHistory_mixedPricedAndMoneyMarket_combinesValues() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var aapl = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), BigDecimal.ZERO);
        var spaxx = new HoldingEntity(brokerageAccount, tenant, "SPAXX",
                new BigDecimal("5000"), BigDecimal.ZERO);
        spaxx.setMoneyMarket(true);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(aapl, spaxx));

        var friday = LocalDate.of(2025, 1, 3);
        var prices = List.of(
                new PriceEntity("AAPL", friday, new BigDecimal("150.0000"), "seed")
        );
        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(prices);

        var result = service.computeHistory(tenantId, accountId, 24);

        assertThat(result.symbols()).containsExactlyInAnyOrder("AAPL", "SPAXX");
        assertThat(result.hasMoneyMarketHoldings()).isTrue();
        assertThat(result.moneyMarketTotal()).isEqualByComparingTo(new BigDecimal("5000"));

        var point = result.dataPoints().stream()
                .filter(dp -> dp.date().equals(friday)).findFirst();
        assertThat(point).isPresent();
        // 10 * 150 + 5000 * 1 = 6500
        assertThat(point.get().totalValue()).isEqualByComparingTo(new BigDecimal("6500.0000"));
    }

    @Test
    void computeHistory_missingPriceForFriday_usesClosestPriorPrice() {
        when(accountRepository.findByTenant_IdAndId(tenantId, accountId))
                .thenReturn(Optional.of(brokerageAccount));

        var holding = new HoldingEntity(brokerageAccount, tenant, "AAPL",
                new BigDecimal("10"), BigDecimal.ZERO);
        when(holdingRepository.findByAccount_IdAndTenant_Id(accountId, tenantId))
                .thenReturn(List.of(holding));

        // Price on Thursday, not Friday
        var thursday = LocalDate.of(2025, 1, 2); // Thursday
        var friday = LocalDate.of(2025, 1, 3); // Friday with no price
        var prices = List.of(
                new PriceEntity("AAPL", thursday, new BigDecimal("148.0000"), "seed")
        );
        when(priceRepository.findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
                eq(List.of("AAPL")), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(prices);

        var result = service.computeHistory(tenantId, accountId, 24);

        // Friday data point should use Thursday's price via floorEntry
        var point = result.dataPoints().stream()
                .filter(dp -> dp.date().equals(friday)).findFirst();
        assertThat(point).isPresent();
        assertThat(point.get().totalValue()).isEqualByComparingTo(new BigDecimal("1480.0000"));
    }
}
