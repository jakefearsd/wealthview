package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyValuationEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.PropertyValuationRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PropertyValuationServiceTest {

    @Mock
    private PropertyValuationRepository valuationRepository;

    @Mock
    private PropertyRepository propertyRepository;

    @InjectMocks
    private PropertyValuationService valuationService;

    private TenantEntity tenant;
    private UUID tenantId;
    private UUID propertyId;
    private PropertyEntity property;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
    }

    @Test
    void recordValuation_newEntry_createsAndUpdatesCurrentValue() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationRepository.findByProperty_IdAndSourceAndValuationDate(
                propertyId, "zillow", LocalDate.of(2025, 3, 1)))
                .thenReturn(Optional.empty());
        when(valuationRepository.save(any(PropertyValuationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(propertyRepository.save(any(PropertyEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = valuationService.recordValuation(tenantId, propertyId,
                LocalDate.of(2025, 3, 1), new BigDecimal("400000"), "zillow");

        assertThat(result.value()).isEqualByComparingTo("400000");
        assertThat(result.source()).isEqualTo("zillow");
        assertThat(property.getCurrentValue()).isEqualByComparingTo("400000");
    }

    @Test
    void recordValuation_existingEntry_updatesValue() {
        var existing = new PropertyValuationEntity(property, tenant,
                LocalDate.of(2025, 3, 1), new BigDecimal("350000"), "zillow");

        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationRepository.findByProperty_IdAndSourceAndValuationDate(
                propertyId, "zillow", LocalDate.of(2025, 3, 1)))
                .thenReturn(Optional.of(existing));
        when(valuationRepository.save(any(PropertyValuationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(propertyRepository.save(any(PropertyEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = valuationService.recordValuation(tenantId, propertyId,
                LocalDate.of(2025, 3, 1), new BigDecimal("400000"), "zillow");

        assertThat(result.value()).isEqualByComparingTo("400000");
    }

    @Test
    void recordValuation_nonExistentProperty_throwsNotFound() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> valuationService.recordValuation(
                tenantId, propertyId, LocalDate.now(), new BigDecimal("400000"), "manual"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getHistory_returnsOrderedValuations() {
        var val1 = new PropertyValuationEntity(property, tenant,
                LocalDate.of(2025, 1, 1), new BigDecimal("340000"), "zillow");
        var val2 = new PropertyValuationEntity(property, tenant,
                LocalDate.of(2025, 2, 1), new BigDecimal("350000"), "zillow");

        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationRepository.findByProperty_IdAndTenant_IdOrderByValuationDateDesc(propertyId, tenantId))
                .thenReturn(List.of(val2, val1));

        var result = valuationService.getHistory(tenantId, propertyId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).valuationDate()).isEqualTo(LocalDate.of(2025, 2, 1));
    }

    @Test
    void getHistory_nonExistentProperty_throwsNotFound() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> valuationService.getHistory(tenantId, propertyId))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
