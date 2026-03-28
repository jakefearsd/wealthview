package com.wealthview.core.config;

import com.wealthview.core.config.dto.SystemStatsResponse;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemStatsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PriceRepository priceRepository;

    @InjectMocks
    private SystemStatsService systemStatsService;

    @Test
    void getStats_withData_returnsCorrectCountsFromRepositories() {
        var tenant = new TenantEntity("Test Tenant");
        var activeUser = new UserEntity(tenant, "active@test.com", "hash", "USER");
        var inactiveUser = new UserEntity(tenant, "inactive@test.com", "hash", "USER");
        inactiveUser.setActive(false);

        when(userRepository.count()).thenReturn(2L);
        when(userRepository.findAll()).thenReturn(List.of(activeUser, inactiveUser));
        when(tenantRepository.count()).thenReturn(1L);
        when(accountRepository.count()).thenReturn(5L);
        when(holdingRepository.count()).thenReturn(10L);
        when(transactionRepository.count()).thenReturn(50L);
        when(priceRepository.findLatestPerSymbol()).thenReturn(List.of(
                new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual"),
                new PriceEntity("GOOG", LocalDate.of(2025, 3, 1), new BigDecimal("150.00"), "manual")
        ));

        SystemStatsResponse result = systemStatsService.getStats();

        assertThat(result.totalUsers()).isEqualTo(2L);
        assertThat(result.activeUsers()).isEqualTo(1L);
        assertThat(result.totalTenants()).isEqualTo(1L);
        assertThat(result.totalAccounts()).isEqualTo(5L);
        assertThat(result.totalHoldings()).isEqualTo(10L);
        assertThat(result.totalTransactions()).isEqualTo(50L);
        assertThat(result.symbolsTracked()).isEqualTo(2L);
    }

    @Test
    void getStats_filtersInactiveUsers_returnsOnlyActiveCount() {
        var tenant = new TenantEntity("Tenant");
        var user1 = new UserEntity(tenant, "a@t.com", "hash", "USER");
        var user2 = new UserEntity(tenant, "b@t.com", "hash", "USER");
        user2.setActive(false);
        var user3 = new UserEntity(tenant, "c@t.com", "hash", "ADMIN");

        when(userRepository.count()).thenReturn(3L);
        when(userRepository.findAll()).thenReturn(List.of(user1, user2, user3));
        when(tenantRepository.count()).thenReturn(1L);
        when(accountRepository.count()).thenReturn(0L);
        when(holdingRepository.count()).thenReturn(0L);
        when(transactionRepository.count()).thenReturn(0L);
        when(priceRepository.findLatestPerSymbol()).thenReturn(List.of());

        SystemStatsResponse result = systemStatsService.getStats();

        assertThat(result.totalUsers()).isEqualTo(3L);
        assertThat(result.activeUsers()).isEqualTo(2L);
    }

    @Test
    void getStats_withNoData_returnsZeros() {
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.findAll()).thenReturn(List.of());
        when(tenantRepository.count()).thenReturn(0L);
        when(accountRepository.count()).thenReturn(0L);
        when(holdingRepository.count()).thenReturn(0L);
        when(transactionRepository.count()).thenReturn(0L);
        when(priceRepository.findLatestPerSymbol()).thenReturn(List.of());

        SystemStatsResponse result = systemStatsService.getStats();

        assertThat(result.totalUsers()).isZero();
        assertThat(result.activeUsers()).isZero();
        assertThat(result.totalTenants()).isZero();
        assertThat(result.totalAccounts()).isZero();
        assertThat(result.totalHoldings()).isZero();
        assertThat(result.totalTransactions()).isZero();
        assertThat(result.symbolsTracked()).isZero();
        assertThat(result.staleSymbols()).isZero();
    }

    @Test
    void getStats_countsUniqueSymbolsFromPriceRepository() {
        var tenant = new TenantEntity("Tenant");

        when(userRepository.count()).thenReturn(0L);
        when(userRepository.findAll()).thenReturn(List.of());
        when(tenantRepository.count()).thenReturn(0L);
        when(accountRepository.count()).thenReturn(0L);
        when(holdingRepository.count()).thenReturn(0L);
        when(transactionRepository.count()).thenReturn(0L);
        when(priceRepository.findLatestPerSymbol()).thenReturn(List.of(
                new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "finnhub"),
                new PriceEntity("GOOG", LocalDate.of(2025, 3, 1), new BigDecimal("150.00"), "finnhub"),
                new PriceEntity("VTI", LocalDate.of(2025, 3, 1), new BigDecimal("250.00"), "manual")
        ));

        SystemStatsResponse result = systemStatsService.getStats();

        assertThat(result.symbolsTracked()).isEqualTo(3L);
    }

    @Test
    void getStats_emptyListsFromRepositories_returnsZeroSymbolsAndDefaults() {
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.findAll()).thenReturn(List.of());
        when(tenantRepository.count()).thenReturn(0L);
        when(accountRepository.count()).thenReturn(0L);
        when(holdingRepository.count()).thenReturn(0L);
        when(transactionRepository.count()).thenReturn(0L);
        when(priceRepository.findLatestPerSymbol()).thenReturn(List.of());

        SystemStatsResponse result = systemStatsService.getStats();

        assertThat(result.symbolsTracked()).isZero();
        assertThat(result.databaseSize()).isEqualTo("N/A");
        assertThat(result.staleSymbols()).isZero();
    }
}
