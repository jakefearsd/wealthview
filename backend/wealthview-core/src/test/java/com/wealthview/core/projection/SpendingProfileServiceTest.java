package com.wealthview.core.projection;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.projection.dto.CreateSpendingProfileRequest;
import com.wealthview.core.projection.dto.SpendingTierRequest;
import com.wealthview.core.projection.dto.UpdateSpendingProfileRequest;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpendingProfileServiceTest {

    @Mock
    private SpendingProfileRepository profileRepository;

    @Mock
    private ProjectionScenarioRepository scenarioRepository;

    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private SpendingProfileService service;

    private UUID tenantId;
    private UUID profileId;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        profileId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    @Test
    void createProfile_validRequest_createsAndReturns() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(profileRepository.save(any(SpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateSpendingProfileRequest(
                "Retirement Spending",
                new BigDecimal("40000"),
                new BigDecimal("20000"),
                null);

        var result = service.createProfile(tenantId, request);

        assertThat(result.name()).isEqualTo("Retirement Spending");
        assertThat(result.essentialExpenses()).isEqualByComparingTo(new BigDecimal("40000"));
        assertThat(result.discretionaryExpenses()).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    void createProfile_tenantNotFound_throws() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        var request = new CreateSpendingProfileRequest(
                "Plan", BigDecimal.ZERO, BigDecimal.ZERO, null);

        assertThatThrownBy(() -> service.createProfile(tenantId, request))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void updateProfile_validRequest_updatesFields() {
        var entity = new SpendingProfileEntity(tenant,"Old Name", new BigDecimal("30000"), new BigDecimal("10000"), "[]");
        when(profileRepository.findByTenant_IdAndId(tenantId, profileId))
                .thenReturn(Optional.of(entity));
        when(profileRepository.save(any(SpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateSpendingProfileRequest(
                "Updated Spending",
                new BigDecimal("45000"),
                new BigDecimal("25000"),
                null);

        var result = service.updateProfile(tenantId, profileId, request);

        assertThat(result.name()).isEqualTo("Updated Spending");
        assertThat(result.essentialExpenses()).isEqualByComparingTo(new BigDecimal("45000"));
        assertThat(result.discretionaryExpenses()).isEqualByComparingTo(new BigDecimal("25000"));
    }

    @Test
    void updateProfile_notFound_throws() {
        when(profileRepository.findByTenant_IdAndId(tenantId, profileId))
                .thenReturn(Optional.empty());

        var request = new UpdateSpendingProfileRequest(
                "Plan", BigDecimal.ZERO, BigDecimal.ZERO, null);

        assertThatThrownBy(() -> service.updateProfile(tenantId, profileId, request))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void listProfiles_returnsList() {
        var entity = new SpendingProfileEntity(tenant,"Plan", new BigDecimal("40000"), new BigDecimal("20000"), "[]");
        when(profileRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(entity));

        var result = service.listProfiles(tenantId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("Plan");
    }

    @Test
    void getProfile_exists_returnsProfile() {
        var entity = new SpendingProfileEntity(tenant,"Plan", new BigDecimal("40000"), new BigDecimal("20000"), "[]");
        when(profileRepository.findByTenant_IdAndId(tenantId, profileId))
                .thenReturn(Optional.of(entity));

        var result = service.getProfile(tenantId, profileId);

        assertThat(result.name()).isEqualTo("Plan");
    }

    @Test
    void getProfile_notFound_throws() {
        when(profileRepository.findByTenant_IdAndId(tenantId, profileId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(tenantId, profileId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deleteProfile_exists_deletes() {
        var entity = new SpendingProfileEntity(tenant,"Plan", new BigDecimal("40000"), new BigDecimal("20000"), "[]");
        when(profileRepository.findByTenant_IdAndId(tenantId, profileId))
                .thenReturn(Optional.of(entity));
        when(scenarioRepository.findBySpendingProfile(entity))
                .thenReturn(List.of());

        service.deleteProfile(tenantId, profileId);

        verify(profileRepository).delete(entity);
    }

    @Test
    void deleteProfile_referencedByScenarios_clearsReferenceThenDeletes() {
        var entity = new SpendingProfileEntity(tenant, "Plan", new BigDecimal("40000"), new BigDecimal("20000"), "[]");
        when(profileRepository.findByTenant_IdAndId(tenantId, profileId))
                .thenReturn(Optional.of(entity));

        var scenario = mock(com.wealthview.persistence.entity.ProjectionScenarioEntity.class);
        when(scenarioRepository.findBySpendingProfile(entity))
                .thenReturn(List.of(scenario));

        service.deleteProfile(tenantId, profileId);

        verify(scenario).setSpendingProfile(null);
        verify(scenarioRepository).saveAll(List.of(scenario));
        verify(profileRepository).delete(entity);
    }

    // === Spending Tier Tests ===

    @Test
    void createProfile_withSpendingTiers_serializesAndReturns() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(profileRepository.save(any(SpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var tiers = List.of(
                new SpendingTierRequest("Conservation", 54, 62,
                        new BigDecimal("96000"), new BigDecimal("0")),
                new SpendingTierRequest("Go-Go", 62, 70,
                        new BigDecimal("156000"), new BigDecimal("60000")));

        var request = new CreateSpendingProfileRequest(
                "Retirement", new BigDecimal("40000"), new BigDecimal("20000"),
                tiers);

        var result = service.createProfile(tenantId, request);

        assertThat(result.spendingTiers()).hasSize(2);
        assertThat(result.spendingTiers().getFirst().name()).isEqualTo("Conservation");
        assertThat(result.spendingTiers().getFirst().essentialExpenses())
                .isEqualByComparingTo(new BigDecimal("96000"));
        assertThat(result.spendingTiers().get(1).name()).isEqualTo("Go-Go");
    }

    @Test
    void createProfile_withNullTiers_returnsEmptyTiersList() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(profileRepository.save(any(SpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateSpendingProfileRequest(
                "Simple", new BigDecimal("40000"), new BigDecimal("20000"),
                null);

        var result = service.createProfile(tenantId, request);

        assertThat(result.spendingTiers()).isEmpty();
    }

    @Test
    void createProfile_withEmptyTiers_returnsEmptyTiersList() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(profileRepository.save(any(SpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateSpendingProfileRequest(
                "Simple", new BigDecimal("40000"), new BigDecimal("20000"),
                List.of());

        var result = service.createProfile(tenantId, request);

        assertThat(result.spendingTiers()).isEmpty();
    }

    @Test
    void updateProfile_withSpendingTiers_updatesTiers() {
        var entity = new SpendingProfileEntity(tenant,"Old", new BigDecimal("30000"), new BigDecimal("10000"), "[]");
        when(profileRepository.findByTenant_IdAndId(tenantId, profileId))
                .thenReturn(Optional.of(entity));
        when(profileRepository.save(any(SpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var tiers = List.of(
                new SpendingTierRequest("Active", 70, 80,
                        new BigDecimal("200000"), new BigDecimal("74000")));

        var request = new UpdateSpendingProfileRequest(
                "Updated", new BigDecimal("45000"), new BigDecimal("25000"),
                tiers);

        var result = service.updateProfile(tenantId, profileId, request);

        assertThat(result.spendingTiers()).hasSize(1);
        assertThat(result.spendingTiers().getFirst().name()).isEqualTo("Active");
        assertThat(result.spendingTiers().getFirst().startAge()).isEqualTo(70);
        assertThat(result.spendingTiers().getFirst().endAge()).isEqualTo(80);
    }

    @Test
    void createProfile_withTierNullEndAge_preservesNullEndAge() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(profileRepository.save(any(SpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var tiers = List.of(
                new SpendingTierRequest("Glide", 80, null,
                        new BigDecimal("250000"), new BigDecimal("118000")));

        var request = new CreateSpendingProfileRequest(
                "Open-ended", new BigDecimal("40000"), new BigDecimal("20000"),
                tiers);

        var result = service.createProfile(tenantId, request);

        assertThat(result.spendingTiers()).hasSize(1);
        assertThat(result.spendingTiers().getFirst().endAge()).isNull();
    }
}
