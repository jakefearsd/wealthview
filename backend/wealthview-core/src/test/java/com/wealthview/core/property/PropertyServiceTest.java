package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.PropertyIncomeRequest;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private PropertyIncomeRepository incomeRepository;

    @Mock
    private PropertyExpenseRepository expenseRepository;

    @Mock
    private TenantRepository tenantRepository;

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
                null, null, null, null, null);
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
                new BigDecimal("300000"), new BigDecimal("6.5"), 360,
                LocalDate.of(2020, 1, 1), true);
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
                new BigDecimal("300000"), null, null, null, null);

        assertThatThrownBy(() -> propertyService.create(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Loan details must be provided in full");
    }

    @Test
    void update_toggleUseComputedBalance_switchesBehavior() {
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        property.setLoanAmount(new BigDecimal("300000"));
        property.setAnnualInterestRate(new BigDecimal("6.5"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));
        property.setUseComputedBalance(false);

        when(propertyRepository.findByTenant_IdAndId(eq(tenantId), any()))
                .thenReturn(Optional.of(property));
        when(propertyRepository.save(any(PropertyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Toggle to computed
        var request = new PropertyRequest("123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"),
                new BigDecimal("300000"), new BigDecimal("6.5"), 360,
                LocalDate.of(2020, 1, 1), true);
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
    void addIncome_validProperty_savesIncome() {
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        when(propertyRepository.findByTenant_IdAndId(eq(tenantId), any()))
                .thenReturn(Optional.of(property));
        when(incomeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        propertyService.addIncome(tenantId, UUID.randomUUID(),
                new PropertyIncomeRequest(LocalDate.now(), new BigDecimal("2000"), "rent", "Monthly rent"));

        verify(incomeRepository).save(any(PropertyIncomeEntity.class));
    }

    @Test
    void getMonthlyCashFlow_calculatesCorrectTotals() {
        var propertyId = UUID.randomUUID();
        var property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var income = new PropertyIncomeEntity(property, tenant,
                LocalDate.of(2025, 1, 15), new BigDecimal("2000"), "rent", null);
        when(incomeRepository.findByProperty_IdAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(income));
        when(expenseRepository.findByProperty_IdAndDateBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = propertyService.getMonthlyCashFlow(tenantId, propertyId,
                YearMonth.of(2025, 1), YearMonth.of(2025, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalIncome()).isEqualByComparingTo("2000");
        assertThat(result.get(0).netCashFlow()).isEqualByComparingTo("2000");
    }

    @Test
    void delete_nonExistent_throwsNotFound() {
        var propertyId = UUID.randomUUID();
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> propertyService.delete(tenantId, propertyId))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
