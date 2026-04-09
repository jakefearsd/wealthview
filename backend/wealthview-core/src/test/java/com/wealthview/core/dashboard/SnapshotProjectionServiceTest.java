package com.wealthview.core.dashboard;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.portfolio.TheoreticalPortfolioService;
import com.wealthview.core.portfolio.dto.PortfolioDataPointDto;
import com.wealthview.core.portfolio.dto.PortfolioHistoryResponse;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotProjectionServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private TheoreticalPortfolioService theoreticalPortfolioService;

    private SnapshotProjectionService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotProjectionService(
                accountRepository, accountService, propertyRepository,
                theoreticalPortfolioService);
    }

    @Test
    void computeProjection_noAccountsOrProperties_returnsEmptyDataPoints() {
        when(accountRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        var result = service.computeProjection(TENANT_ID, 10, 10);

        assertThat(result.dataPoints()).isEmpty();
        assertThat(result.projectionYears()).isEqualTo(10);
        assertThat(result.investmentAccountCount()).isZero();
        assertThat(result.propertyCount()).isZero();
        assertThat(result.portfolioCagr()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeProjection_singleBrokerageWithHistory_projectsWithCagr() {
        var account = mockAccount("brokerage");
        var accountId = account.getId();
        when(accountRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(account));
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        // Simulate 5 years of history: $10,000 → $16,105.10 (10% CAGR)
        var startDate = LocalDate.now().minusYears(5);
        var endDate = LocalDate.now();
        var history = new PortfolioHistoryResponse(accountId,
                List.of(
                        new PortfolioDataPointDto(startDate, new BigDecimal("10000")),
                        new PortfolioDataPointDto(endDate, new BigDecimal("16105.10"))
                ),
                List.of("VTI"), 260, false, null);
        when(theoreticalPortfolioService.computeHistory(TENANT_ID, accountId, 120))
                .thenReturn(history);

        var result = service.computeProjection(TENANT_ID, 10, 10);

        assertThat(result.dataPoints()).hasSize(11); // year 0 through 10
        assertThat(result.investmentAccountCount()).isEqualTo(1);

        // Year 0 = current value
        var year0 = result.dataPoints().get(0);
        assertThat(year0.year()).isZero();
        assertThat(year0.investmentValue()).isEqualByComparingTo(new BigDecimal("16105.10"));

        // Year 1 should be ~10% higher
        var year1 = result.dataPoints().get(1);
        assertThat(year1.investmentValue().doubleValue()).isCloseTo(17715.61, org.assertj.core.data.Offset.offset(100.0));

        // Values should increase over time
        for (int i = 1; i < result.dataPoints().size(); i++) {
            assertThat(result.dataPoints().get(i).investmentValue())
                    .isGreaterThanOrEqualTo(result.dataPoints().get(i - 1).investmentValue());
        }
    }

    @Test
    void computeProjection_bankAccount_projectedFlat() {
        var account = mockAccount("bank");
        var bankId = account.getId();
        when(accountRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(account));
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        // Bank account with $5000 balance
        when(accountService.computeAllBalances(TENANT_ID))
                .thenReturn(Map.of(bankId, new BigDecimal("5000")));

        var result = service.computeProjection(TENANT_ID, 5, 10);

        assertThat(result.dataPoints()).hasSize(6); // year 0 through 5

        // All years should have the same investment value (flat)
        for (var dp : result.dataPoints()) {
            assertThat(dp.investmentValue()).isEqualByComparingTo(new BigDecimal("5000"));
        }
    }

    @Test
    void computeProjection_accountWithNoHistory_zeroPercentGrowth() {
        var account = mockAccount("brokerage");
        var accountId = account.getId();
        when(accountRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(account));
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        // Empty history response
        var history = new PortfolioHistoryResponse(accountId, List.of(), List.of(), 0, false, null);
        when(theoreticalPortfolioService.computeHistory(TENANT_ID, accountId, 120))
                .thenReturn(history);

        var result = service.computeProjection(TENANT_ID, 5, 10);

        // All years should be 0 (no current value to project)
        for (var dp : result.dataPoints()) {
            assertThat(dp.investmentValue()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void computeProjection_propertyWithAppreciation_compoundsForward() {
        when(accountRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        var property = mockProperty(
                new BigDecimal("300000"), // current value
                new BigDecimal("0.03"),   // 3% appreciation
                BigDecimal.ZERO,          // no mortgage
                false                     // no loan details
        );
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));

        var result = service.computeProjection(TENANT_ID, 5, 10);

        assertThat(result.dataPoints()).hasSize(6);
        assertThat(result.propertyCount()).isEqualTo(1);

        // Year 0: equity = 300000 - 0 = 300000
        assertThat(result.dataPoints().get(0).propertyEquity())
                .isEqualByComparingTo(new BigDecimal("300000"));

        // Year 1: 300000 * 1.03 = 309000
        assertThat(result.dataPoints().get(1).propertyEquity().setScale(0, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("309000"));

        // Year 5: 300000 * 1.03^5 = 347782.49...
        assertThat(result.dataPoints().get(5).propertyEquity().doubleValue())
                .isCloseTo(347782.49, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void computeProjection_propertyWithNullAppreciation_staysFlat() {
        when(accountRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        var property = mockProperty(
                new BigDecimal("250000"), // current value
                null,                     // null appreciation rate
                new BigDecimal("100000"), // static mortgage
                false                     // no loan details
        );
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));

        var result = service.computeProjection(TENANT_ID, 5, 10);

        // All years: equity = 250000 - 100000 = 150000 (flat, no appreciation, static mortgage)
        for (var dp : result.dataPoints()) {
            assertThat(dp.propertyEquity()).isEqualByComparingTo(new BigDecimal("150000"));
        }
    }

    @Test
    void computeProjection_mortgagePayoffMidProjection_equityEqualsFullValue() {
        when(accountRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        // Property with loan that will be paid off within projection period
        // Loan started 28 years ago, 30 year term → 2 years left
        var property = mockPropertyWithLoan(
                new BigDecimal("400000"),  // current value
                null,                      // no appreciation
                new BigDecimal("200000"),  // original loan amount
                new BigDecimal("0.04"),    // 4% interest
                360,                       // 30 year term
                LocalDate.now().minusYears(28) // started 28 years ago
        );
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));

        var result = service.computeProjection(TENANT_ID, 5, 10);

        // After payoff (year 3+), equity should equal full property value
        var year5 = result.dataPoints().get(5);
        assertThat(year5.propertyEquity()).isEqualByComparingTo(new BigDecimal("400000"));

        // Year 0 should have some remaining mortgage balance
        var year0 = result.dataPoints().get(0);
        assertThat(year0.propertyEquity()).isLessThan(new BigDecimal("400000"));
    }

    @Test
    void computeProjection_mortgageWithoutLoanDetails_usesStaticBalance() {
        when(accountRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of());

        var property = mockProperty(
                new BigDecimal("500000"),
                new BigDecimal("0.04"),
                new BigDecimal("200000"), // static mortgage balance
                false                      // no loan details
        );
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));

        var result = service.computeProjection(TENANT_ID, 5, 10);

        // Year 0: equity = 500000 - 200000 = 300000
        assertThat(result.dataPoints().get(0).propertyEquity())
                .isEqualByComparingTo(new BigDecimal("300000"));

        // Year 5: 500000 * 1.04^5 - 200000 (static) = ~408163 - 200000 = ~408163
        var year5 = result.dataPoints().get(5);
        assertThat(year5.propertyEquity().doubleValue()).isCloseTo(408326.0, org.assertj.core.data.Offset.offset(100.0));
    }

    @Test
    void computeProjection_mixedPortfolio_totalsAggregateCorrectly() {
        var brokerageAccount = mockAccount("brokerage");
        var bankAccount = mockAccount("bank");
        when(accountRepository.findByTenant_Id(TENANT_ID))
                .thenReturn(List.of(brokerageAccount, bankAccount));

        // Brokerage: flat CAGR (identical start/end over 1 year -> 0% growth), current value $50,000
        var now = LocalDate.now();
        var history = new PortfolioHistoryResponse(brokerageAccount.getId(),
                List.of(
                        new PortfolioDataPointDto(now.minusYears(1), new BigDecimal("50000")),
                        new PortfolioDataPointDto(now, new BigDecimal("50000"))
                ),
                List.of("VTI"), 52, false, null);
        when(theoreticalPortfolioService.computeHistory(TENANT_ID, brokerageAccount.getId(), 120))
                .thenReturn(history);

        // Bank: $10,000 balance
        var bankId = bankAccount.getId();
        when(accountService.computeAllBalances(TENANT_ID))
                .thenReturn(Map.of(bankId, new BigDecimal("10000")));

        // Property: $300,000 value, no mortgage, no appreciation
        var property = mockProperty(new BigDecimal("300000"), null, BigDecimal.ZERO, false);
        when(propertyRepository.findByTenant_Id(TENANT_ID)).thenReturn(List.of(property));

        var result = service.computeProjection(TENANT_ID, 5, 10);

        assertThat(result.investmentAccountCount()).isEqualTo(2);
        assertThat(result.propertyCount()).isEqualTo(1);

        // Year 0: investments = 50000 + 10000 = 60000, property = 300000, total = 360000
        var year0 = result.dataPoints().get(0);
        assertThat(year0.investmentValue()).isEqualByComparingTo(new BigDecimal("60000"));
        assertThat(year0.propertyEquity()).isEqualByComparingTo(new BigDecimal("300000"));
        assertThat(year0.totalValue()).isEqualByComparingTo(new BigDecimal("360000"));
    }

    // --- Helper methods ---

    private AccountEntity mockAccount(String type) {
        var account = mock(AccountEntity.class);
        var id = UUID.randomUUID();
        lenient().when(account.getId()).thenReturn(id);
        when(account.getType()).thenReturn(type);
        return account;
    }

    private PropertyEntity mockProperty(BigDecimal currentValue, BigDecimal appreciationRate,
                                         BigDecimal mortgageBalance, boolean hasLoanDetails) {
        var property = mock(PropertyEntity.class);
        when(property.getCurrentValue()).thenReturn(currentValue);
        when(property.getAnnualAppreciationRate()).thenReturn(appreciationRate);
        when(property.hasLoanDetails()).thenReturn(hasLoanDetails);
        if (!hasLoanDetails) {
            when(property.getMortgageBalance()).thenReturn(mortgageBalance);
        }
        return property;
    }

    private PropertyEntity mockPropertyWithLoan(BigDecimal currentValue, BigDecimal appreciationRate,
                                                 BigDecimal loanAmount, BigDecimal interestRate,
                                                 int termMonths, LocalDate loanStartDate) {
        var property = mock(PropertyEntity.class);
        when(property.getCurrentValue()).thenReturn(currentValue);
        when(property.getAnnualAppreciationRate()).thenReturn(appreciationRate);
        when(property.hasLoanDetails()).thenReturn(true);
        when(property.getLoanAmount()).thenReturn(loanAmount);
        when(property.getAnnualInterestRate()).thenReturn(interestRate);
        when(property.getLoanTermMonths()).thenReturn(termMonths);
        when(property.getLoanStartDate()).thenReturn(loanStartDate);
        return property;
    }
}
