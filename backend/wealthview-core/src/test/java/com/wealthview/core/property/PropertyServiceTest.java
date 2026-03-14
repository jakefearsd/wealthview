package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.PropertyExpenseRequest;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
class PropertyServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private PropertyExpenseRepository expenseRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PropertyService propertyService;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    @Test
    void create_validRequest_returnsPropertyResponse() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null);
        var result = propertyService.create(tenantId, request);

        assertThat(result.address()).isEqualTo("123 Main St");
        assertThat(result.equity()).isEqualByComparingTo("150000");
    }

    @Test
    void create_withLoanDetails_computesMortgageBalance() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                new BigDecimal("300000"), new BigDecimal("0.065"), 360,
                LocalDate.of(2020, 1, 1), true, null,
                null, null, null, null,
                null, null, null, null);
        var result = propertyService.create(tenantId, request);

        assertThat(result.hasLoanDetails()).isTrue();
        assertThat(result.useComputedBalance()).isTrue();
        // Balance should be computed, not the manual 200000
        assertThat(result.mortgageBalance()).isNotEqualByComparingTo("200000");
    }

    @Test
    void create_withPartialLoanDetails_throwsValidation() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                new BigDecimal("300000"), null, null, null, null, null,
                null, null, null, null,
                null, null, null, null);

        assertThatThrownBy(() -> propertyService.create(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Loan details must be provided in full");
    }

    @Test
    void update_toggleUseComputedBalance_switchesBehavior() {
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setLoanAmount(new BigDecimal("300000"));
        property.setAnnualInterestRate(new BigDecimal("0.065"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));
        property.setUseComputedBalance(false);

        when(propertyRepository.findByTenant_IdAndId(eq(tenantId), any()))
                .thenReturn(Optional.of(property));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Toggle to computed
        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                new BigDecimal("300000"), new BigDecimal("0.065"), 360,
                LocalDate.of(2020, 1, 1), true, null,
                null, null, null, null,
                null, null, null, null);
        var result = propertyService.update(tenantId, UUID.randomUUID(), request);

        assertThat(result.useComputedBalance()).isTrue();
        // Should use computed balance, not manual 200000
        assertThat(result.mortgageBalance()).isNotEqualByComparingTo("200000");
    }

    @Test
    void list_mixedProperties_correctEquityForEach() {
        var manual = new PropertyEntity(tenant, "Manual Property", new BigDecimal("200000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("250000"), new BigDecimal("100000"));

        var computed = new PropertyEntity(tenant, "Computed Property", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        computed.setLoanAmount(new BigDecimal("300000"));
        computed.setAnnualInterestRate(new BigDecimal("6.5"));
        computed.setLoanTermMonths(360);
        computed.setLoanStartDate(LocalDate.of(2020, 1, 1));
        computed.setUseComputedBalance(true);

        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of(manual, computed));

        var result = propertyService.list(tenantId);

        assertThat(result).hasSize(2);
        // Manual: equity = 250000 - 100000 = 150000
        assertThat(result.get(0).equity()).isEqualByComparingTo("150000");
        // Computed: equity uses amortization, should differ from manual balance
        assertThat(result.get(1).useComputedBalance()).isTrue();
        assertThat(result.get(1).mortgageBalance()).isNotEqualByComparingTo("200000");
    }

    @Test
    void list_returnsTenantProperties() {
        var property = new PropertyEntity(tenant, "456 Oak Ave", new BigDecimal("250000"),
                LocalDate.of(2019, 6, 15), new BigDecimal("280000"), new BigDecimal("150000"));
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of(property));

        var result = propertyService.list(tenantId);

        assertThat(result).hasSize(1);
    }

    @Test
    void getMonthlyCashFlow_expensesOnly_zeroIncome() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var expense = new PropertyExpenseEntity(property, tenant,
                LocalDate.of(2025, 1, 15), new BigDecimal("500"), "maintenance", null);
        when(expenseRepository.findOverlapping(any(), any(), any(), any()))
                .thenReturn(List.of(expense));

        var result = propertyService.getMonthlyCashFlow(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalExpenses()).isEqualByComparingTo("500");
        assertThat(result.get(0).netCashFlow()).isEqualByComparingTo("-500");
    }

    @Test
    void create_withPropertyType_setsPropertyType() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, "investment",
                null, null, null, null,
                null, null, null, null);
        var result = propertyService.create(tenantId, request);

        assertThat(result.propertyType()).isEqualTo("investment");
    }

    @Test
    void create_withoutPropertyType_defaultsToPrimaryResidence() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null);
        var result = propertyService.create(tenantId, request);

        assertThat(result.propertyType()).isEqualTo("primary_residence");
    }

    @Test
    void create_withInvalidPropertyType_throwsIllegalArgument() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, "commercial",
                null, null, null, null,
                null, null, null, null);

        assertThatThrownBy(() -> propertyService.create(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid property type");
    }

    @Test
    void update_changesPropertyType() {
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setPropertyType("primary_residence");

        when(propertyRepository.findByTenant_IdAndId(eq(tenantId), any()))
                .thenReturn(Optional.of(property));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, "vacation",
                null, null, null, null,
                null, null, null, null);
        var result = propertyService.update(tenantId, UUID.randomUUID(), request);

        assertThat(result.propertyType()).isEqualTo("vacation");
    }

    @Test
    void delete_nonExistent_throwsNotFound() {
        var propertyId = UUID.randomUUID();
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> propertyService.delete(tenantId, propertyId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void addExpense_withAnnualFrequency_savesWithFrequency() {
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        when(propertyRepository.findByTenant_IdAndId(eq(tenantId), any()))
                .thenReturn(Optional.of(property));
        when(expenseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        propertyService.addExpense(tenantId, UUID.randomUUID(),
                new PropertyExpenseRequest(LocalDate.of(2025, 1, 1), new BigDecimal("6000"), "tax", null, "annual"));

        var captor = ArgumentCaptor.forClass(PropertyExpenseEntity.class);
        verify(expenseRepository).save(captor.capture());
        assertThat(captor.getValue().getFrequency()).isEqualTo("annual");
    }

    @Test
    void getMonthlyCashFlow_annualExpense_spreadsAcrossMonths() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var annualExpense = new PropertyExpenseEntity(property, tenant,
                LocalDate.of(2025, 1, 1), new BigDecimal("6000"), "tax", null, "annual");
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(List.of(annualExpense));

        var result = propertyService.getMonthlyCashFlow(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 12));

        assertThat(result).hasSize(12);
        for (var entry : result) {
            assertThat(entry.totalExpenses()).isEqualByComparingTo("500");
        }
    }

    @Test
    void getMonthlyCashFlow_withAnnualCosts_includesDerivedExpenses() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setAnnualPropertyTax(new BigDecimal("6000"));
        property.setAnnualInsuranceCost(new BigDecimal("2400"));
        property.setAnnualMaintenanceCost(new BigDecimal("1200"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = propertyService.getMonthlyCashFlow(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 3));

        assertThat(result).hasSize(3);
        // 500 + 200 + 100 = 800 per month
        for (var entry : result) {
            assertThat(entry.totalExpenses()).isEqualByComparingTo("800");
            assertThat(entry.netCashFlow()).isEqualByComparingTo("-800");
        }
    }

    @Test
    void getMonthlyCashFlow_withLoanDetails_includesMortgageInExpenses() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setLoanAmount(new BigDecimal("300000"));
        property.setAnnualInterestRate(new BigDecimal("0.065"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = propertyService.getMonthlyCashFlow(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalExpenses()).isEqualByComparingTo("1896.2041");
    }

    @Test
    void getMonthlyCashFlow_derivedPlusManual_sumsCorrectly() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setAnnualPropertyTax(new BigDecimal("6000"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var manualExpense = new PropertyExpenseEntity(property, tenant,
                LocalDate.of(2025, 1, 15), new BigDecimal("300"), "maintenance", null);
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(List.of(manualExpense));

        var result = propertyService.getMonthlyCashFlow(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 2));

        assertThat(result).hasSize(2);
        // Jan: 500 (derived tax) + 300 (manual maintenance) = 800
        assertThat(result.get(0).totalExpenses()).isEqualByComparingTo("800");
        // Feb: 500 (derived tax only)
        assertThat(result.get(1).totalExpenses()).isEqualByComparingTo("500");
    }

    @Test
    void create_withDepreciationFields_setsDepreciation() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, "investment",
                null, null, null, null,
                LocalDate.of(2020, 6, 15), new BigDecimal("50000"), "straight_line", new BigDecimal("27.5"));
        var result = propertyService.create(tenantId, request);

        assertThat(result.depreciationMethod()).isEqualTo("straight_line");
        assertThat(result.inServiceDate()).isEqualTo(LocalDate.of(2020, 6, 15));
        assertThat(result.landValue()).isEqualByComparingTo("50000");
        assertThat(result.usefulLifeYears()).isEqualByComparingTo("27.5");
    }

    @Test
    void create_withNullDepreciationMethod_defaultsToNone() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null);
        var result = propertyService.create(tenantId, request);

        assertThat(result.depreciationMethod()).isEqualTo("none");
    }

    @Test
    void create_withInvalidDepreciationMethod_throwsIllegalArgument() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, "bogus", null);

        assertThatThrownBy(() -> propertyService.create(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid depreciation method");
    }

    @Test
    void create_withFinancialFields_mapsFieldsToResponse() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, null,
                new BigDecimal("0.03000"), new BigDecimal("4500.0000"), new BigDecimal("1800.0000"), new BigDecimal("2400.0000"),
                null, null, null, null);
        var result = propertyService.create(tenantId, request);

        assertThat(result.annualAppreciationRate()).isEqualByComparingTo("0.03000");
        assertThat(result.annualPropertyTax()).isEqualByComparingTo("4500");
        assertThat(result.annualInsuranceCost()).isEqualByComparingTo("1800");
        assertThat(result.annualMaintenanceCost()).isEqualByComparingTo("2400");
    }

    @Test
    void create_withNullFinancialFields_returnsNulls() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null);
        var result = propertyService.create(tenantId, request);

        assertThat(result.annualAppreciationRate()).isNull();
        assertThat(result.annualPropertyTax()).isNull();
        assertThat(result.annualInsuranceCost()).isNull();
        assertThat(result.annualMaintenanceCost()).isNull();
    }

    @Test
    void getMonthlyCashFlowDetail_groupsExpensesByCategory() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var mortgage = new PropertyExpenseEntity(property, tenant,
                LocalDate.of(2025, 3, 1), new BigDecimal("1200"), "mortgage", null);
        var insurance = new PropertyExpenseEntity(property, tenant,
                LocalDate.of(2025, 3, 15), new BigDecimal("150"), "insurance", null);
        var tax = new PropertyExpenseEntity(property, tenant,
                LocalDate.of(2025, 1, 1), new BigDecimal("3600"), "tax", null, "annual");
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(List.of(mortgage, insurance, tax));

        var result = propertyService.getMonthlyCashFlowDetail(tenantId, propertyId,
                YearMonth.of(2025, 3), YearMonth.of(2025, 3));

        assertThat(result).hasSize(1);
        var march = result.get(0);
        assertThat(march.month()).isEqualTo("2025-03");
        assertThat(march.expensesByCategory()).containsEntry("mortgage", new BigDecimal("1200"));
        assertThat(march.expensesByCategory()).containsEntry("insurance", new BigDecimal("150"));
        assertThat(march.expensesByCategory().get("tax")).isEqualByComparingTo("300");
        assertThat(march.totalExpenses()).isEqualByComparingTo("1650");
        assertThat(march.netCashFlow()).isEqualByComparingTo("-1650");
    }

    @Test
    void getMonthlyCashFlowDetail_emptyData_returnsZeroEntries() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = propertyService.getMonthlyCashFlowDetail(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 3));

        assertThat(result).hasSize(3);
        for (var entry : result) {
            assertThat(entry.totalIncome()).isEqualByComparingTo("0");
            assertThat(entry.totalExpenses()).isEqualByComparingTo("0");
            assertThat(entry.expensesByCategory()).isEmpty();
        }
    }

    @Test
    void getMonthlyCashFlowDetail_withAnnualPropertyTax_addsDerivedTaxExpense() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setAnnualPropertyTax(new BigDecimal("6000"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = propertyService.getMonthlyCashFlowDetail(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).expensesByCategory().get("tax")).isEqualByComparingTo("500");
        assertThat(result.get(0).totalExpenses()).isEqualByComparingTo("500");
    }

    @Test
    void getMonthlyCashFlowDetail_withAllAnnualCosts_addsAllDerivedCategories() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setAnnualPropertyTax(new BigDecimal("6000"));
        property.setAnnualInsuranceCost(new BigDecimal("2400"));
        property.setAnnualMaintenanceCost(new BigDecimal("1200"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = propertyService.getMonthlyCashFlowDetail(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));

        assertThat(result).hasSize(1);
        var categories = result.get(0).expensesByCategory();
        assertThat(categories.get("tax")).isEqualByComparingTo("500");
        assertThat(categories.get("insurance")).isEqualByComparingTo("200");
        assertThat(categories.get("maintenance")).isEqualByComparingTo("100");
        assertThat(result.get(0).totalExpenses()).isEqualByComparingTo("800");
    }

    @Test
    void getMonthlyCashFlowDetail_withLoanDetails_addsDerivedMortgagePayment() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setLoanAmount(new BigDecimal("300000"));
        property.setAnnualInterestRate(new BigDecimal("0.065"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = propertyService.getMonthlyCashFlowDetail(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).expensesByCategory().get("mortgage")).isEqualByComparingTo("1896.2041");
        assertThat(result.get(0).totalExpenses()).isEqualByComparingTo("1896.2041");
    }

    @Test
    void getMonthlyCashFlowDetail_derivedExpensesMergeWithManual() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setAnnualPropertyTax(new BigDecimal("6000"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        // Manual tax expense of 100 in January
        var manualTax = new PropertyExpenseEntity(property, tenant,
                LocalDate.of(2025, 1, 15), new BigDecimal("100"), "tax", null);
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(List.of(manualTax));

        var result = propertyService.getMonthlyCashFlowDetail(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 2));

        assertThat(result).hasSize(2);
        // Jan: 500 (derived) + 100 (manual) = 600
        assertThat(result.get(0).expensesByCategory().get("tax")).isEqualByComparingTo("600");
        // Feb: 500 (derived only)
        assertThat(result.get(1).expensesByCategory().get("tax")).isEqualByComparingTo("500");
    }

    @Test
    void getMonthlyCashFlowDetail_nullAnnualFields_noDerivedExpenses() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        // All annual fields null (default)
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = propertyService.getMonthlyCashFlowDetail(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).expensesByCategory()).isEmpty();
        assertThat(result.get(0).totalExpenses()).isEqualByComparingTo("0");
    }

    @Test
    void getMonthlyCashFlowDetail_withLoanAndManualMortgage_mergesAmounts() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setLoanAmount(new BigDecimal("300000"));
        property.setAnnualInterestRate(new BigDecimal("0.065"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var manualMortgage = new PropertyExpenseEntity(property, tenant,
                LocalDate.of(2025, 1, 15), new BigDecimal("200"), "mortgage", null);
        when(expenseRepository.findOverlapping(eq(propertyId), any(), any(), any()))
                .thenReturn(List.of(manualMortgage));

        var result = propertyService.getMonthlyCashFlowDetail(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));

        assertThat(result).hasSize(1);
        // 1896.2041 (derived) + 200 (manual) = 2096.2041
        assertThat(result.get(0).expensesByCategory().get("mortgage")).isEqualByComparingTo("2096.2041");
    }

    @Test
    void update_withFinancialFields_updatesFields() {
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));

        when(propertyRepository.findByTenant_IdAndId(eq(tenantId), any()))
                .thenReturn(Optional.of(property));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                null, null, null, null, null, null,
                new BigDecimal("0.04000"), new BigDecimal("5200.0000"), new BigDecimal("2100.0000"), new BigDecimal("3000.0000"),
                null, null, null, null);
        var result = propertyService.update(tenantId, UUID.randomUUID(), request);

        assertThat(result.annualAppreciationRate()).isEqualByComparingTo("0.04");
        assertThat(result.annualPropertyTax()).isEqualByComparingTo("5200");
        assertThat(result.annualInsuranceCost()).isEqualByComparingTo("2100");
        assertThat(result.annualMaintenanceCost()).isEqualByComparingTo("3000");
    }
}
