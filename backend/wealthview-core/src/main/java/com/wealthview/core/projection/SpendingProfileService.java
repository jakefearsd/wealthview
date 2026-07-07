package com.wealthview.core.projection;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.common.Entities;
import com.wealthview.core.projection.dto.CreateSpendingProfileRequest;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.projection.dto.SpendingTierRequest;
import com.wealthview.core.projection.dto.UpdateSpendingProfileRequest;
import com.wealthview.core.tenant.TenantLookup;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SpendingProfileService {

    private static final Logger log = LoggerFactory.getLogger(SpendingProfileService.class);

    private final SpendingProfileRepository profileRepository;
    private final ProjectionScenarioRepository scenarioRepository;
    private final TenantLookup tenantLookup;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpendingProfileService(SpendingProfileRepository profileRepository,
                                   ProjectionScenarioRepository scenarioRepository,
                                   TenantLookup tenantLookup) {
        this.profileRepository = profileRepository;
        this.scenarioRepository = scenarioRepository;
        this.tenantLookup = tenantLookup;
    }

    @Transactional
    public SpendingProfileResponse createProfile(UUID tenantId, CreateSpendingProfileRequest request) {
        var tenant = tenantLookup.requireTenant(tenantId);

        var entity = new SpendingProfileEntity(
                tenant,
                request.name(),
                request.essentialExpenses(),
                request.discretionaryExpenses(),
                serializeSpendingTiers(request.spendingTiers()));

        var saved = profileRepository.save(entity);
        log.info("Spending profile '{}' created for tenant {}", request.name(), tenantId);
        return SpendingProfileResponse.from(saved);
    }

    @Transactional
    public SpendingProfileResponse updateProfile(UUID tenantId, UUID profileId, UpdateSpendingProfileRequest request) {
        var entity = profileRepository.findByTenant_IdAndId(tenantId, profileId)
                .orElseThrow(Entities.notFound("Spending profile"));

        entity.setName(request.name());
        entity.setEssentialExpenses(request.essentialExpenses());
        entity.setDiscretionaryExpenses(request.discretionaryExpenses());
        entity.setSpendingTiers(serializeSpendingTiers(request.spendingTiers()));

        var saved = profileRepository.save(entity);
        log.info("Spending profile {} updated for tenant {}", profileId, tenantId);
        return SpendingProfileResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SpendingProfileResponse> listProfiles(UUID tenantId) {
        return profileRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId).stream()
                .map(SpendingProfileResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SpendingProfileResponse getProfile(UUID tenantId, UUID profileId) {
        var entity = profileRepository.findByTenant_IdAndId(tenantId, profileId)
                .orElseThrow(Entities.notFound("Spending profile"));
        return SpendingProfileResponse.from(entity);
    }

    @Transactional
    public void deleteProfile(UUID tenantId, UUID profileId) {
        var entity = profileRepository.findByTenant_IdAndId(tenantId, profileId)
                .orElseThrow(Entities.notFound("Spending profile"));

        var referencingScenarios = scenarioRepository.findBySpendingProfile(entity);
        if (!referencingScenarios.isEmpty()) {
            referencingScenarios.forEach(s -> s.setSpendingProfile(null));
            scenarioRepository.saveAll(referencingScenarios);
            log.info("Cleared spending profile reference from {} scenario(s)", referencingScenarios.size());
        }

        profileRepository.delete(entity);
        log.info("Spending profile {} deleted for tenant {}", profileId, tenantId);
    }

    private String serializeSpendingTiers(List<SpendingTierRequest> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(tiers);
        } catch (JacksonException e) {
            log.warn("Failed to serialize spending tiers", e);
            return "[]";
        }
    }
}
