package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.entity.PropertyValuationEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.PropertyValuationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PropertyAnalyticsServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private PropertyIncomeRepository incomeRepository;

    @Mock
    private PropertyExpenseRepository expenseRepository;

    @Mock
    private PropertyValuationRepository valuationRepository;

    private PropertyAnalyticsService analyticsService;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        analyticsService = new PropertyAnalyticsService(
                propertyRepository, incomeRepository, expenseRepository, valuationRepository);
    }

    @Test
    void getAnalytics_propertyNotFound_throwsNotFound() {
        var propertyId = UUID.randomUUID();
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> analyticsService.getAnalytics(tenantId, propertyId, null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getAnalytics_primaryResidence_returnsAppreciationAndEquityGrowth() {
        var property = createProperty("primary_residence", "350000", "300000");
        property.setPurchaseDate(LocalDate.of(2024, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.propertyType()).isEqualTo("primary_residence");
        assertThat(result.totalAppreciation()).isEqualByComparingTo("50000");
        assertThat(result.appreciationPercent()).isPositive();
        assertThat(result.equityGrowth()).isNotEmpty();
    }

    @Test
    void getAnalytics_primaryResidence_investmentFieldsAreNull() {
        var property = createProperty("primary_residence", "350000", "300000");
        property.setPurchaseDate(LocalDate.of(2024, 6, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.capRate()).isNull();
        assertThat(result.annualNoi()).isNull();
        assertThat(result.cashOnCashReturn()).isNull();
        assertThat(result.annualNetCashFlow()).isNull();
        assertThat(result.totalCashInvested()).isNull();
    }

    @Test
    void getAnalytics_primaryResidenceWithLoan_returnsMortgageProgress() {
        var property = createProperty("primary_residence", "350000", "300000");
        property.setPurchaseDate(LocalDate.of(2020, 1, 1));
        property.setLoanAmount(new BigDecimal("240000"));
        property.setAnnualInterestRate(new BigDecimal("0.06"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.mortgageProgress()).isNotNull();
        assertThat(result.mortgageProgress().originalLoanAmount()).isEqualByComparingTo("240000");
        assertThat(result.mortgageProgress().currentBalance()).isLessThan(new BigDecimal("240000"));
        assertThat(result.mortgageProgress().principalPaid()).isPositive();
        assertThat(result.mortgageProgress().percentPaidOff()).isPositive();
        assertThat(result.mortgageProgress().estimatedPayoffDate()).isEqualTo(LocalDate.of(2050, 1, 1));
        assertThat(result.mortgageProgress().monthsRemaining()).isPositive();
    }

    @Test
    void getAnalytics_primaryResidenceWithoutLoan_mortgageProgressIsNull() {
        var property = createProperty("primary_residence", "350000", "300000");
        property.setPurchaseDate(LocalDate.of(2024, 6, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.mortgageProgress()).isNull();
    }

    @Test
    void getAnalytics_investment_returnsCapRateAndCashOnCash() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2023, 1, 1));
        property.setLoanAmount(new BigDecimal("280000"));
        property.setAnnualInterestRate(new BigDecimal("0.07"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2023, 1, 1));
        property.setMortgageBalance(new BigDecimal("270000"));
        mockProperty(property);
        mockEmptyValuations(property);

        // Mock income: $2500/month rent for trailing 12 months
        var incomes = List.of(
                createIncome(property, LocalDate.now().minusMonths(6), "2500"),
                createIncome(property, LocalDate.now().minusMonths(3), "2500"),
                createIncome(property, LocalDate.now().minusMonths(1), "2500")
        );
        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(incomes);

        // Mock operating expenses (non-mortgage): $500 tax, $200 insurance
        var opExpenses = List.of(
                createExpense(property, LocalDate.now().minusMonths(6), "500", "tax"),
                createExpense(property, LocalDate.now().minusMonths(3), "200", "insurance")
        );
        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(opExpenses);

        // Mock all expenses (including mortgage): add mortgage on top
        var allExpenses = List.of(
                createExpense(property, LocalDate.now().minusMonths(6), "500", "tax"),
                createExpense(property, LocalDate.now().minusMonths(3), "200", "insurance"),
                createExpense(property, LocalDate.now().minusMonths(6), "1800", "mortgage"),
                createExpense(property, LocalDate.now().minusMonths(3), "1800", "mortgage")
        );
        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(allExpenses);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.capRate()).isNotNull();
        assertThat(result.annualNoi()).isNotNull();
        assertThat(result.cashOnCashReturn()).isNotNull();
        assertThat(result.annualNetCashFlow()).isNotNull();
        // Cash invested = purchasePrice - loanAmount = 350000 - 280000 = 70000
        assertThat(result.totalCashInvested()).isEqualByComparingTo("70000");
    }

    @Test
    void getAnalytics_investmentNoIncome_returnsZeroCapRate() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2024, 6, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.capRate()).isEqualByComparingTo("0");
        assertThat(result.annualNoi()).isEqualByComparingTo("0");
    }

    @Test
    void getAnalytics_withSpecificYear_usesYearRange() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2023, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = analyticsService.getAnalytics(tenantId, property.getId(), 2024);

        assertThat(result.propertyType()).isEqualTo("investment");
        assertThat(result.capRate()).isNotNull();
    }

    @Test
    void getAnalytics_vacationProperty_investmentFieldsAreNull() {
        var property = createProperty("vacation", "500000", "450000");
        property.setPurchaseDate(LocalDate.of(2024, 6, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.propertyType()).isEqualTo("vacation");
        assertThat(result.capRate()).isNull();
        assertThat(result.cashOnCashReturn()).isNull();
    }

    @Test
    void getAnalytics_withValuations_usesValuationForEquityGrowth() {
        var property = createProperty("primary_residence", "400000", "300000");
        property.setPurchaseDate(LocalDate.of(2024, 1, 1));
        property.setLoanAmount(new BigDecimal("240000"));
        property.setAnnualInterestRate(new BigDecimal("0.06"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2024, 1, 1));
        mockProperty(property);

        // Create valuations at specific dates
        var val1 = new PropertyValuationEntity(property, tenant,
                LocalDate.of(2024, 6, 15), new BigDecimal("350000"), "zillow");
        var val2 = new PropertyValuationEntity(property, tenant,
                LocalDate.of(2024, 3, 10), new BigDecimal("320000"), "zillow");

        // Valuations ordered by date DESC
        when(valuationRepository.findByProperty_IdAndTenant_IdOrderByValuationDateDesc(any(), eq(tenantId)))
                .thenReturn(List.of(val1, val2));

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.equityGrowth()).isNotEmpty();
        // March 2024 should use val2 (320000), June 2024 should use val1 (350000)
        var marchPoint = result.equityGrowth().stream()
                .filter(p -> p.month().equals("2024-03")).findFirst();
        assertThat(marchPoint).isPresent();
        assertThat(marchPoint.get().propertyValue()).isEqualByComparingTo("320000");

        var junePoint = result.equityGrowth().stream()
                .filter(p -> p.month().equals("2024-06")).findFirst();
        assertThat(junePoint).isPresent();
        assertThat(junePoint.get().propertyValue()).isEqualByComparingTo("350000");
    }

    @Test
    void getAnalytics_investmentWithoutLoan_cashInvestedEqualsPurchasePrice() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2024, 6, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        // No loan, so cash invested = full purchase price
        assertThat(result.totalCashInvested()).isEqualByComparingTo("350000");
    }

    @Test
    void getAnalytics_appreciationPercent_calculatedCorrectly() {
        var property = createProperty("primary_residence", "360000", "300000");
        property.setPurchaseDate(LocalDate.of(2024, 6, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        // (60000 / 300000) * 100 = 20.0000
        assertThat(result.totalAppreciation()).isEqualByComparingTo("60000");
        assertThat(result.appreciationPercent()).isEqualByComparingTo("20.0000");
    }

    @Test
    void getAnalytics_depreciatedProperty_negativeAppreciation() {
        var property = createProperty("primary_residence", "250000", "300000");
        property.setPurchaseDate(LocalDate.of(2024, 6, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.totalAppreciation()).isNegative();
        assertThat(result.appreciationPercent()).isNegative();
    }

    @Test
    void getAnalytics_investmentCapRate_calculatedCorrectly() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2023, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        // Annual income = 24000 (12 * 2000 rent), Operating expenses = 6000 (tax + insurance)
        // NOI = 24000 - 6000 = 18000
        // Cap Rate = (18000 / 400000) * 100 = 4.5000
        var incomes = List.of(
                createIncome(property, LocalDate.now().minusMonths(1), "24000")
        );
        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(incomes);

        var opExpenses = List.of(
                createExpense(property, LocalDate.now().minusMonths(1), "6000", "tax")
        );
        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(opExpenses);
        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(opExpenses);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.annualNoi()).isEqualByComparingTo("18000");
        assertThat(result.capRate()).isEqualByComparingTo("4.5000");
    }

    @Test
    void getAnalytics_equityGrowthWithoutLoan_usesManualBalance() {
        var property = createProperty("primary_residence", "350000", "300000");
        property.setPurchaseDate(LocalDate.of(2024, 10, 1));
        property.setMortgageBalance(new BigDecimal("200000"));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        // Without loan details, all equity points use manual mortgage balance (200000)
        for (var point : result.equityGrowth()) {
            assertThat(point.mortgageBalance()).isEqualByComparingTo("200000");
        }
    }

    @Test
    void getAnalytics_paidOffLoan_mortgageProgressShowsComplete() {
        var property = createProperty("primary_residence", "500000", "200000");
        property.setPurchaseDate(LocalDate.of(1990, 1, 1));
        property.setLoanAmount(new BigDecimal("160000"));
        property.setAnnualInterestRate(new BigDecimal("0.08"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(1990, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.mortgageProgress()).isNotNull();
        assertThat(result.mortgageProgress().currentBalance()).isEqualByComparingTo("0");
        assertThat(result.mortgageProgress().principalPaid()).isEqualByComparingTo("160000");
        assertThat(result.mortgageProgress().percentPaidOff()).isEqualByComparingTo("100.0000");
        assertThat(result.mortgageProgress().monthsRemaining()).isZero();
    }

    @Test
    void getAnalytics_investmentCashOnCash_calculatedCorrectly() {
        // Setup: investment property with known values
        // Purchase: 350000, Loan: 280000 → Cash invested = 70000
        // Income: 30000, Operating expenses: 6000 → NOI = 24000
        // All expenses (incl mortgage): 6000 + 18000 = 24000 → Net cash flow = 30000 - 24000 = 6000
        // Cash-on-cash = (6000 / 70000) * 100 = 8.5714
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2023, 1, 1));
        property.setLoanAmount(new BigDecimal("280000"));
        property.setAnnualInterestRate(new BigDecimal("0.07"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2023, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(List.of(createIncome(property, LocalDate.now().minusMonths(1), "30000")));

        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(List.of(createExpense(property, LocalDate.now().minusMonths(1), "6000", "tax")));

        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(List.of(
                        createExpense(property, LocalDate.now().minusMonths(1), "6000", "tax"),
                        createExpense(property, LocalDate.now().minusMonths(1), "18000", "mortgage")
                ));

        var result = analyticsService.getAnalytics(tenantId, property.getId(), null);

        assertThat(result.annualNoi()).isEqualByComparingTo("24000");
        assertThat(result.capRate()).isEqualByComparingTo("6.0000"); // (24000/400000)*100
        assertThat(result.annualNetCashFlow()).isEqualByComparingTo("6000"); // 30000 - 24000
        assertThat(result.totalCashInvested()).isEqualByComparingTo("70000"); // 350000 - 280000
        assertThat(result.cashOnCashReturn()).isEqualByComparingTo("8.5714"); // (6000/70000)*100
    }

    @Test
    void getAnalytics_withSpecificYear_passesCorrectDateRangeToRepository() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2022, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        analyticsService.getAnalytics(tenantId, property.getId(), 2024);

        // Verify repository was called with Jan 1 to Dec 31 of 2024, annual from = Feb 1 2023
        verify(incomeRepository).findOverlapping(
                eq(property.getId()),
                eq(LocalDate.of(2024, 1, 1)),
                eq(LocalDate.of(2024, 12, 31)),
                eq(LocalDate.of(2023, 2, 1))
        );
        verify(expenseRepository).findOverlappingExcludingCategory(
                eq(property.getId()),
                eq(LocalDate.of(2024, 1, 1)),
                eq(LocalDate.of(2024, 12, 31)),
                eq(LocalDate.of(2023, 2, 1)),
                eq("mortgage")
        );
        verify(expenseRepository).findOverlapping(
                eq(property.getId()),
                eq(LocalDate.of(2024, 1, 1)),
                eq(LocalDate.of(2024, 12, 31)),
                eq(LocalDate.of(2023, 2, 1))
        );
    }

    @Test
    void getAnalytics_investmentWithAnnualExpense_proratesCorrectly() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2023, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        // Annual income of $30,000 starting Jan 2025, analysis year = 2025
        // Full 12 months overlap → full amount used
        var annualIncome = createIncome(property, LocalDate.of(2025, 1, 1), "30000", "annual");
        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(List.of(annualIncome));

        // Annual tax of $6,000 starting Jan 2025 — fully overlaps
        var annualTax = createExpense(property, LocalDate.of(2025, 1, 1), "6000", "tax", "annual");
        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(List.of(annualTax));

        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(List.of(annualTax));

        var result = analyticsService.getAnalytics(tenantId, property.getId(), 2025);

        // NOI = 30000 - 6000 = 24000
        assertThat(result.annualNoi()).isEqualByComparingTo("24000");
        // Cap rate = (24000 / 400000) * 100 = 6.0000
        assertThat(result.capRate()).isEqualByComparingTo("6.0000");
    }

    @Test
    void getAnalytics_annualExpensePartialOverlap_proratedCorrectly() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2023, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        // Annual insurance of $12,000 starting Jul 2025 — covers Jul 2025 to Jun 2026
        // Analysis year = 2025 (Jan-Dec): 6 months overlap (Jul-Dec)
        // Prorated amount = 12000 * 6/12 = 6000
        var annualInsurance = createExpense(property, LocalDate.of(2025, 7, 1), "12000", "insurance", "annual");
        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(List.of(annualInsurance));

        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(List.of(annualInsurance));

        var result = analyticsService.getAnalytics(tenantId, property.getId(), 2025);

        // NOI = 0 - 6000 = -6000
        assertThat(result.annualNoi()).isEqualByComparingTo("-6000");
        // Net cash flow = 0 - 6000 = -6000
        assertThat(result.annualNetCashFlow()).isEqualByComparingTo("-6000");
    }

    @Test
    void getAnalytics_mixedMonthlyAndAnnual_sumsCorrectly() {
        var property = createProperty("investment", "400000", "350000");
        property.setPurchaseDate(LocalDate.of(2023, 1, 1));
        mockProperty(property);
        mockEmptyValuations(property);

        // Monthly income entries + annual entry
        var monthlyIncome = createIncome(property, LocalDate.now().minusMonths(1), "2000");
        var annualIncome = createIncome(property, LocalDate.of(2025, 1, 1), "12000", "annual");
        when(incomeRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(List.of(monthlyIncome, annualIncome));

        when(expenseRepository.findOverlappingExcludingCategory(
                eq(property.getId()), any(), any(), any(), eq("mortgage")))
                .thenReturn(Collections.emptyList());
        when(expenseRepository.findOverlapping(eq(property.getId()), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = analyticsService.getAnalytics(tenantId, property.getId(), 2025);

        // Total income = 2000 (monthly) + 12000 (annual, full overlap) = 14000
        assertThat(result.annualNoi()).isEqualByComparingTo("14000");
    }

    // --- Helper methods ---

    private PropertyEntity createProperty(String propertyType, String currentValue, String purchasePrice) {
        var property = new PropertyEntity(tenant, "123 Test St", new BigDecimal(purchasePrice),
                LocalDate.of(2020, 1, 1), new BigDecimal(currentValue), BigDecimal.ZERO);
        property.setPropertyType(propertyType);
        return property;
    }

    private void mockProperty(PropertyEntity property) {
        when(propertyRepository.findByTenant_IdAndId(eq(tenantId), any()))
                .thenReturn(Optional.of(property));
    }

    private void mockEmptyValuations(PropertyEntity property) {
        when(valuationRepository.findByProperty_IdAndTenant_IdOrderByValuationDateDesc(any(), eq(tenantId)))
                .thenReturn(Collections.emptyList());
    }

    private PropertyIncomeEntity createIncome(PropertyEntity property, LocalDate date, String amount) {
        return new PropertyIncomeEntity(property, tenant, date, new BigDecimal(amount), "rent", null);
    }

    private PropertyIncomeEntity createIncome(PropertyEntity property, LocalDate date, String amount, String frequency) {
        return new PropertyIncomeEntity(property, tenant, date, new BigDecimal(amount), "rent", null, frequency);
    }

    private PropertyExpenseEntity createExpense(PropertyEntity property, LocalDate date,
                                                 String amount, String category) {
        return new PropertyExpenseEntity(property, tenant, date, new BigDecimal(amount), category, null);
    }

    private PropertyExpenseEntity createExpense(PropertyEntity property, LocalDate date,
                                                 String amount, String category, String frequency) {
        return new PropertyExpenseEntity(property, tenant, date, new BigDecimal(amount), category, null, frequency);
    }
}
