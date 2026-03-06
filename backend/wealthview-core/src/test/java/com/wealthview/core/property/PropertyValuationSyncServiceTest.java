package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.PropertyValuationResponse;
import com.wealthview.core.property.dto.PropertyValuationResult;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PropertyValuationSyncServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private PropertyValuationClient valuationClient;

    @Mock
    private PropertyValuationService valuationService;

    @InjectMocks
    private PropertyValuationSyncService syncService;

    private TenantEntity tenant;
    private UUID tenantId;
    private PropertyEntity property;
    private UUID propertyId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        property = new PropertyEntity(tenant, "123 Main St", new BigDecimal("300000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("350000"), new BigDecimal("200000"));
    }

    @Test
    void syncAll_withResults_recordsValuations() {
        com.wealthview.core.testutil.TestEntityHelper.setId(tenant, tenantId);
        com.wealthview.core.testutil.TestEntityHelper.setId(property, propertyId);

        when(propertyRepository.findAll()).thenReturn(List.of(property));
        when(valuationClient.getValuation("123 Main St"))
                .thenReturn(Optional.of(new PropertyValuationResult(
                        new BigDecimal("400000"), LocalDate.of(2025, 3, 1))));
        when(valuationService.recordValuation(any(), any(), any(), any(), any()))
                .thenReturn(new PropertyValuationResponse(UUID.randomUUID(),
                        LocalDate.of(2025, 3, 1), new BigDecimal("400000"), "zillow"));

        syncService.syncAll();

        verify(valuationService).recordValuation(
                eq(tenantId), eq(propertyId),
                eq(LocalDate.of(2025, 3, 1)), eq(new BigDecimal("400000")), eq("zillow"));
    }

    @Test
    void syncAll_emptyResult_skipsProperty() {
        com.wealthview.core.testutil.TestEntityHelper.setId(tenant, tenantId);
        com.wealthview.core.testutil.TestEntityHelper.setId(property, propertyId);

        when(propertyRepository.findAll()).thenReturn(List.of(property));
        when(valuationClient.getValuation("123 Main St")).thenReturn(Optional.empty());

        syncService.syncAll();

        verify(valuationService, never()).recordValuation(any(), any(), any(), any(), any());
    }

    @Test
    void syncSingleProperty_withResult_recordsValuation() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationClient.getValuation("123 Main St"))
                .thenReturn(Optional.of(new PropertyValuationResult(
                        new BigDecimal("400000"), LocalDate.of(2025, 3, 1))));
        when(valuationService.recordValuation(any(), any(), any(), any(), any()))
                .thenReturn(new PropertyValuationResponse(UUID.randomUUID(),
                        LocalDate.of(2025, 3, 1), new BigDecimal("400000"), "zillow"));

        syncService.syncSingleProperty(tenantId, propertyId);

        verify(valuationService).recordValuation(
                eq(tenantId), eq(propertyId),
                eq(LocalDate.of(2025, 3, 1)), eq(new BigDecimal("400000")), eq("zillow"));
    }

    @Test
    void syncSingleProperty_nonExistent_throwsNotFound() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> syncService.syncSingleProperty(tenantId, propertyId))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
