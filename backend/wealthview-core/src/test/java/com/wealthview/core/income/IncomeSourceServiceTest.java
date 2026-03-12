package com.wealthview.core.income;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.income.dto.CreateIncomeSourceRequest;
import com.wealthview.core.income.dto.IncomeSourceResponse;
import com.wealthview.core.income.dto.UpdateIncomeSourceRequest;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncomeSourceServiceTest {

    @Mock
    private IncomeSourceRepository incomeSourceRepository;

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private IncomeSourceService service;

    private UUID tenantId;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    @Test
    void create_validRequest_createsAndReturns() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(incomeSourceRepository.save(any(IncomeSourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateIncomeSourceRequest(
                "Social Security", "social_security", new BigDecimal("30000"),
                67, null, new BigDecimal("0.02"), false, "partially_taxable", null);

        var result = service.create(tenantId, request);

        assertThat(result.name()).isEqualTo("Social Security");
        assertThat(result.incomeType()).isEqualTo("social_security");
        assertThat(result.annualAmount()).isEqualByComparingTo("30000");
        assertThat(result.startAge()).isEqualTo(67);
        assertThat(result.endAge()).isNull();
        assertThat(result.inflationRate()).isEqualByComparingTo("0.02");
        assertThat(result.oneTime()).isFalse();
        assertThat(result.taxTreatment()).isEqualTo("partially_taxable");
        assertThat(result.propertyId()).isNull();
    }

    @Test
    void create_withPropertyLink_setsProperty() {
        var propertyId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        var property = new PropertyEntity(tenant, "123 Elm St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), BigDecimal.ZERO);
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(incomeSourceRepository.save(any(IncomeSourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateIncomeSourceRequest(
                "Rental Income", "rental_property", new BigDecimal("24000"),
                60, null, BigDecimal.ZERO, false, "rental_passive", propertyId);

        var result = service.create(tenantId, request);

        assertThat(result.incomeType()).isEqualTo("rental_property");
        assertThat(result.taxTreatment()).isEqualTo("rental_passive");
        assertThat(result.propertyAddress()).isEqualTo("123 Elm St");
    }

    @Test
    void create_withInvalidPropertyId_throwsEntityNotFound() {
        var propertyId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.empty());

        var request = new CreateIncomeSourceRequest(
                "Rental Income", "rental_property", new BigDecimal("24000"),
                60, null, BigDecimal.ZERO, false, "rental_passive", propertyId);

        assertThatThrownBy(() -> service.create(tenantId, request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Property not found");
    }

    @Test
    void create_invalidIncomeType_throwsIllegalArgument() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new CreateIncomeSourceRequest(
                "Bad Type", "invalid_type", new BigDecimal("10000"),
                65, null, BigDecimal.ZERO, false, "taxable", null);

        assertThatThrownBy(() -> service.create(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid income type");
    }

    @Test
    void create_invalidTaxTreatment_throwsIllegalArgument() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new CreateIncomeSourceRequest(
                "Pension", "pension", new BigDecimal("20000"),
                65, null, BigDecimal.ZERO, false, "invalid_treatment", null);

        assertThatThrownBy(() -> service.create(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid tax treatment");
    }

    @Test
    void create_invalidTenant_throwsInvalidSession() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        var request = new CreateIncomeSourceRequest(
                "SS", "social_security", new BigDecimal("30000"),
                67, null, BigDecimal.ZERO, false, "taxable", null);

        assertThatThrownBy(() -> service.create(tenantId, request))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void list_returnsSortedIncomeSources() {
        var entity = new IncomeSourceEntity(tenant, "SS", "social_security",
                new BigDecimal("30000"), 67, null, BigDecimal.ZERO, false, "partially_taxable");
        when(incomeSourceRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(entity));

        var result = service.list(tenantId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("SS");
    }

    @Test
    void get_existing_returnsIncomeSource() {
        var sourceId = UUID.randomUUID();
        var entity = new IncomeSourceEntity(tenant, "Pension", "pension",
                new BigDecimal("25000"), 65, null, new BigDecimal("0.02"), false, "taxable");
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId))
                .thenReturn(Optional.of(entity));

        var result = service.get(tenantId, sourceId);

        assertThat(result.name()).isEqualTo("Pension");
        assertThat(result.incomeType()).isEqualTo("pension");
    }

    @Test
    void get_nonexistent_throwsEntityNotFound() {
        var sourceId = UUID.randomUUID();
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(tenantId, sourceId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Income source not found");
    }

    @Test
    void update_validRequest_updatesFields() {
        var sourceId = UUID.randomUUID();
        var entity = new IncomeSourceEntity(tenant, "Old Name", "pension",
                new BigDecimal("20000"), 65, null, BigDecimal.ZERO, false, "taxable");
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId))
                .thenReturn(Optional.of(entity));
        when(incomeSourceRepository.save(any(IncomeSourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateIncomeSourceRequest(
                "Updated Pension", "pension", new BigDecimal("28000"), 66, 90,
                new BigDecimal("0.03"), false, "taxable", null);

        var result = service.update(tenantId, sourceId, request);

        assertThat(result.name()).isEqualTo("Updated Pension");
        assertThat(result.annualAmount()).isEqualByComparingTo("28000");
        assertThat(result.startAge()).isEqualTo(66);
        assertThat(result.endAge()).isEqualTo(90);
        assertThat(result.inflationRate()).isEqualByComparingTo("0.03");
    }

    @Test
    void update_withPropertyLink_updatesProperty() {
        var sourceId = UUID.randomUUID();
        var propertyId = UUID.randomUUID();
        var entity = new IncomeSourceEntity(tenant, "Rental", "rental_property",
                new BigDecimal("24000"), 60, null, BigDecimal.ZERO, false, "rental_passive");
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId))
                .thenReturn(Optional.of(entity));
        var property = new PropertyEntity(tenant, "456 Oak Ave", new BigDecimal("400000"),
                LocalDate.of(2019, 6, 1), new BigDecimal("450000"), BigDecimal.ZERO);
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(incomeSourceRepository.save(any(IncomeSourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateIncomeSourceRequest(
                "Rental", "rental_property", new BigDecimal("26000"), 60, null,
                BigDecimal.ZERO, false, "rental_active_reps", propertyId);

        var result = service.update(tenantId, sourceId, request);

        assertThat(result.taxTreatment()).isEqualTo("rental_active_reps");
        assertThat(result.propertyAddress()).isEqualTo("456 Oak Ave");
    }

    @Test
    void update_nonexistent_throwsEntityNotFound() {
        var sourceId = UUID.randomUUID();
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId))
                .thenReturn(Optional.empty());

        var request = new UpdateIncomeSourceRequest(
                "Name", null, new BigDecimal("10000"), 65, null, BigDecimal.ZERO, false, "taxable", null);

        assertThatThrownBy(() -> service.update(tenantId, sourceId, request))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void delete_existing_deletesSuccessfully() {
        var sourceId = UUID.randomUUID();
        var entity = new IncomeSourceEntity(tenant, "SS", "social_security",
                new BigDecimal("30000"), 67, null, BigDecimal.ZERO, false, "taxable");
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId))
                .thenReturn(Optional.of(entity));

        service.delete(tenantId, sourceId);

        verify(incomeSourceRepository).delete(entity);
    }

    @Test
    void delete_nonexistent_throwsEntityNotFound() {
        var sourceId = UUID.randomUUID();
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(tenantId, sourceId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void create_withNullOneTime_defaultsToFalse() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(incomeSourceRepository.save(any(IncomeSourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateIncomeSourceRequest(
                "Bonus", "other", new BigDecimal("5000"),
                60, 60, BigDecimal.ZERO, null, "taxable", null);

        var result = service.create(tenantId, request);

        assertThat(result.oneTime()).isFalse();
    }

    @Test
    void create_withNullInflationRate_defaultsToZero() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(incomeSourceRepository.save(any(IncomeSourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateIncomeSourceRequest(
                "Side Income", "other", new BigDecimal("10000"),
                55, 65, null, false, "taxable", null);

        var result = service.create(tenantId, request);

        assertThat(result.inflationRate()).isEqualByComparingTo("0");
    }

    @Test
    void create_withNullTaxTreatment_defaultsToTaxable() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(incomeSourceRepository.save(any(IncomeSourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateIncomeSourceRequest(
                "Other Income", "other", new BigDecimal("10000"),
                55, null, BigDecimal.ZERO, false, null, null);

        var result = service.create(tenantId, request);

        assertThat(result.taxTreatment()).isEqualTo("taxable");
    }
}
