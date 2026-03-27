package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.property.dto.PropertyValuationResponse;
import com.wealthview.core.property.dto.PropertyValuationResult;
import com.wealthview.core.property.dto.ValuationRefreshResponse;
import com.wealthview.core.property.dto.ZillowSearchResult;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.PropertyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    private PropertyValuationSyncService syncService;

    private TenantEntity tenant;
    private UUID tenantId;
    private PropertyEntity property;
    private UUID propertyId;

    @BeforeEach
    void setUp() {
        syncService = new PropertyValuationSyncService(
                propertyRepository, valuationClient, valuationService, new SimpleMeterRegistry());
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
    void syncSingleProperty_nonExistent_throwsNotFound() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> syncService.refreshProperty(tenantId, propertyId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void refreshProperty_withZpid_fetchesDirectlyAndReturnsUpdated() {
        property.setZillowZpid("12345");
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationClient.getValuationByZpid("12345"))
                .thenReturn(Optional.of(new PropertyValuationResult(
                        new BigDecimal("400000"), LocalDate.of(2025, 3, 1))));
        when(valuationService.recordValuation(any(), any(), any(), any(), any()))
                .thenReturn(new PropertyValuationResponse(UUID.randomUUID(),
                        LocalDate.of(2025, 3, 1), new BigDecimal("400000"), "zillow"));

        var result = syncService.refreshProperty(tenantId, propertyId);

        assertThat(result.status()).isEqualTo("updated");
        assertThat(result.value()).isEqualByComparingTo("400000");
        verify(valuationClient).getValuationByZpid("12345");
        verify(valuationClient, never()).searchProperties(any());
    }

    @Test
    void refreshProperty_noZpid_singleSearchResult_autoSelectsAndStoresZpid() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationClient.searchProperties("123 Main St"))
                .thenReturn(List.of(new ZillowSearchResult("99999", "123 Main St, City, ST 12345",
                        new BigDecimal("400000"))));
        when(valuationClient.getValuationByZpid("99999"))
                .thenReturn(Optional.of(new PropertyValuationResult(
                        new BigDecimal("400000"), LocalDate.of(2025, 3, 1))));
        when(valuationService.recordValuation(any(), any(), any(), any(), any()))
                .thenReturn(new PropertyValuationResponse(UUID.randomUUID(),
                        LocalDate.of(2025, 3, 1), new BigDecimal("400000"), "zillow"));

        var result = syncService.refreshProperty(tenantId, propertyId);

        assertThat(result.status()).isEqualTo("updated");
        assertThat(result.value()).isEqualByComparingTo("400000");
        assertThat(property.getZillowZpid()).isEqualTo("99999");
        verify(propertyRepository).save(property);
    }

    @Test
    void refreshProperty_noZpid_multipleSearchResults_returnsCandidates() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));

        var candidates = List.of(
                new ZillowSearchResult("111", "123 Main St Unit A, City, ST", new BigDecimal("350000")),
                new ZillowSearchResult("222", "123 Main St Unit B, City, ST", new BigDecimal("375000"))
        );
        when(valuationClient.searchProperties("123 Main St")).thenReturn(candidates);

        var result = syncService.refreshProperty(tenantId, propertyId);

        assertThat(result.status()).isEqualTo("multiple_matches");
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.value()).isNull();
        verify(valuationService, never()).recordValuation(any(), any(), any(), any(), any());
    }

    @Test
    void refreshProperty_noZpid_noSearchResults_returnsNoResults() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationClient.searchProperties("123 Main St")).thenReturn(Collections.emptyList());

        var result = syncService.refreshProperty(tenantId, propertyId);

        assertThat(result.status()).isEqualTo("no_results");
        verify(valuationService, never()).recordValuation(any(), any(), any(), any(), any());
    }

    @Test
    void selectZpid_storesZpidAndRecordsValuation() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationClient.getValuationByZpid("55555"))
                .thenReturn(Optional.of(new PropertyValuationResult(
                        new BigDecimal("425000"), LocalDate.of(2025, 3, 1))));
        when(valuationService.recordValuation(any(), any(), any(), any(), any()))
                .thenReturn(new PropertyValuationResponse(UUID.randomUUID(),
                        LocalDate.of(2025, 3, 1), new BigDecimal("425000"), "zillow"));

        var result = syncService.selectZpid(tenantId, propertyId, "55555");

        assertThat(result.status()).isEqualTo("updated");
        assertThat(result.value()).isEqualByComparingTo("425000");
        assertThat(property.getZillowZpid()).isEqualTo("55555");
        verify(propertyRepository).save(property);
    }

    @Test
    void selectZpid_zpidReturnsNoValuation_returnsNoResults() {
        when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
                .thenReturn(Optional.of(property));
        when(valuationClient.getValuationByZpid("55555"))
                .thenReturn(Optional.empty());

        var result = syncService.selectZpid(tenantId, propertyId, "55555");

        assertThat(result.status()).isEqualTo("no_results");
        // zpid should still be stored even if current fetch fails
        assertThat(property.getZillowZpid()).isEqualTo("55555");
    }
}
