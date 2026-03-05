package com.wealthview.core.dashboard;

import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private PriceRepository priceRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private DashboardService dashboardService;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        TestEntityHelper.setId(tenant, tenantId);
    }

    @Test
    void getSummary_withHoldingsAndPrices_calculatesNetWorth() {
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        TestEntityHelper.setId(account, UUID.randomUUID());
        var holding = new HoldingEntity(account, tenant, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500"));
        var price = new PriceEntity("AAPL", LocalDate.now(), new BigDecimal("200"), "manual");

        when(accountRepository.findByTenantId(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(account)));
        when(holdingRepository.findByTenantId(tenantId)).thenReturn(List.of(holding));
        when(priceRepository.findFirstBySymbolOrderByDateDesc("AAPL"))
                .thenReturn(Optional.of(price));

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.totalInvestments()).isEqualByComparingTo("2000.0000");
        assertThat(result.netWorth()).isEqualByComparingTo("2000.0000");
    }

    @Test
    void getSummary_missingPrice_fallsBackToCostBasis() {
        var account = new AccountEntity(tenant, "IRA", "ira", "Vanguard");
        TestEntityHelper.setId(account, UUID.randomUUID());
        var holding = new HoldingEntity(account, tenant, "VTI",
                new BigDecimal("5"), new BigDecimal("1000"));

        when(accountRepository.findByTenantId(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(account)));
        when(holdingRepository.findByTenantId(tenantId)).thenReturn(List.of(holding));
        when(priceRepository.findFirstBySymbolOrderByDateDesc("VTI"))
                .thenReturn(Optional.empty());

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.totalInvestments()).isEqualByComparingTo("1000");
    }

    @Test
    void getSummary_withBankAccounts_includesCashBalance() {
        var bank = new AccountEntity(tenant, "Checking", "bank", "Chase");
        TestEntityHelper.setId(bank, UUID.randomUUID());
        var deposit = new TransactionEntity(bank, tenant, LocalDate.now(), "deposit",
                null, null, new BigDecimal("5000"));
        var withdrawal = new TransactionEntity(bank, tenant, LocalDate.now(), "withdrawal",
                null, null, new BigDecimal("1000"));

        when(accountRepository.findByTenantId(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bank)));
        when(holdingRepository.findByTenantId(tenantId)).thenReturn(Collections.emptyList());
        when(transactionRepository.findByAccountIdAndTenantId(any(), eq(tenantId), any()))
                .thenReturn(new PageImpl<>(List.of(deposit, withdrawal)));

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.totalCash()).isEqualByComparingTo("4000");
        assertThat(result.netWorth()).isEqualByComparingTo("4000");
    }

    @Test
    void getSummary_emptyTenant_returnsZeros() {
        when(accountRepository.findByTenantId(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(holdingRepository.findByTenantId(tenantId)).thenReturn(Collections.emptyList());

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.netWorth()).isEqualByComparingTo("0");
        assertThat(result.totalInvestments()).isEqualByComparingTo("0");
        assertThat(result.totalCash()).isEqualByComparingTo("0");
    }
}
