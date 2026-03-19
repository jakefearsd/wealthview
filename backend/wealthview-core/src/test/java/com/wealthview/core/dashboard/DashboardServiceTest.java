package com.wealthview.core.dashboard;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.PropertyRepository;
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
    private AccountService accountService;

    @Mock
    private PropertyRepository propertyRepository;

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

        when(accountRepository.findByTenant_Id(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(account)));
        when(accountService.computeBalance(account, tenantId))
                .thenReturn(new BigDecimal("2000.0000"));

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.totalInvestments()).isEqualByComparingTo("2000.0000");
        assertThat(result.netWorth()).isEqualByComparingTo("2000.0000");
    }

    @Test
    void getSummary_missingPrice_fallsBackToCostBasis() {
        var account = new AccountEntity(tenant, "IRA", "ira", "Vanguard");
        TestEntityHelper.setId(account, UUID.randomUUID());

        when(accountRepository.findByTenant_Id(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(account)));
        when(accountService.computeBalance(account, tenantId))
                .thenReturn(new BigDecimal("1000"));

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.totalInvestments()).isEqualByComparingTo("1000");
    }

    @Test
    void getSummary_withBankAccounts_includesCashBalance() {
        var bank = new AccountEntity(tenant, "Checking", "bank", "Chase");
        TestEntityHelper.setId(bank, UUID.randomUUID());

        when(accountRepository.findByTenant_Id(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bank)));
        when(accountService.computeBalance(bank, tenantId))
                .thenReturn(new BigDecimal("4000"));

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.totalCash()).isEqualByComparingTo("4000");
        assertThat(result.netWorth()).isEqualByComparingTo("4000");
    }

    @Test
    void getSummary_withProperties_includesPropertyEquityInNetWorth() {
        var property = new PropertyEntity(tenant, "123 Main St",
                new BigDecimal("300000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("350000"), new BigDecimal("200000"));

        when(accountRepository.findByTenant_Id(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of(property));

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.totalPropertyEquity()).isEqualByComparingTo("150000");
        assertThat(result.netWorth()).isEqualByComparingTo("150000");
        assertThat(result.accounts()).hasSize(1);
        assertThat(result.accounts().get(0).name()).isEqualTo("123 Main St");
        assertThat(result.accounts().get(0).type()).isEqualTo("property");
        assertThat(result.accounts().get(0).balance()).isEqualByComparingTo("150000");
        assertThat(result.allocation()).anyMatch(a -> a.category().equals("property"));
    }

    @Test
    void getSummary_withInvestmentsAndProperties_combinesNetWorth() {
        var account = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        TestEntityHelper.setId(account, UUID.randomUUID());
        var property = new PropertyEntity(tenant, "456 Oak Ave",
                new BigDecimal("250000"), LocalDate.of(2021, 6, 15),
                new BigDecimal("280000"), new BigDecimal("180000"));

        when(accountRepository.findByTenant_Id(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(account)));
        when(accountService.computeBalance(account, tenantId))
                .thenReturn(new BigDecimal("1500"));
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of(property));

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.totalInvestments()).isEqualByComparingTo("1500");
        assertThat(result.totalPropertyEquity()).isEqualByComparingTo("100000");
        assertThat(result.netWorth()).isEqualByComparingTo("101500");
        assertThat(result.accounts()).hasSize(2);
    }

    @Test
    void getSummary_withComputedMortgageBalance_usesAmortization() {
        var property = new PropertyEntity(tenant, "789 Elm St",
                new BigDecimal("400000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("450000"), new BigDecimal("300000"));
        property.setLoanAmount(new BigDecimal("350000"));
        property.setAnnualInterestRate(new BigDecimal("0.065"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));
        property.setUseComputedBalance(true);

        when(accountRepository.findByTenant_Id(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of(property));

        var result = dashboardService.getSummary(tenantId);

        // Should NOT use the manual 300000 balance
        assertThat(result.totalPropertyEquity()).isNotEqualByComparingTo("150000");
        assertThat(result.totalPropertyEquity()).isPositive();
    }

    @Test
    void getSummary_emptyTenant_returnsZeros() {
        when(accountRepository.findByTenant_Id(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(Collections.emptyList());

        var result = dashboardService.getSummary(tenantId);

        assertThat(result.netWorth()).isEqualByComparingTo("0");
        assertThat(result.totalInvestments()).isEqualByComparingTo("0");
        assertThat(result.totalCash()).isEqualByComparingTo("0");
        assertThat(result.totalPropertyEquity()).isEqualByComparingTo("0");
    }
}
