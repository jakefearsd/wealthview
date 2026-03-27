package com.wealthview.core.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.projection.dto.CreateSpendingProfileRequest;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.projection.dto.SpendingTierRequest;
import com.wealthview.core.projection.dto.UpdateSpendingProfileRequest;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SpendingProfileService {

    private static final Logger log = LoggerFactory.getLogger(SpendingProfileService.class);

    private final SpendingProfileRepository profileRepository;
    private final ProjectionScenarioRepository scenarioRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpendingProfileService(SpendingProfileRepository profileRepository,
                                   ProjectionScenarioRepository scenarioRepository,
                                   TenantRepository tenantRepository) {
        this.profileRepository = profileRepository;
        this.scenarioRepository = scenarioRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public SpendingProfileResponse createProfile(UUID tenantId, CreateSpendingProfileRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

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
                .orElseThrow(() -> new EntityNotFoundException("Spending profile not found"));

        entity.setName(request.name());
        entity.setEssentialExpenses(request.essentialExpenses());
        entity.setDiscretionaryExpenses(request.discretionaryExpenses());
        entity.setSpendingTiers(serializeSpendingTiers(request.spendingTiers()));
        entity.setUpdatedAt(OffsetDateTime.now());

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
                .orElseThrow(() -> new EntityNotFoundException("Spending profile not found"));
        return SpendingProfileResponse.from(entity);
    }

    @Transactional
    public void deleteProfile(UUID tenantId, UUID profileId) {
        var entity = profileRepository.findByTenant_IdAndId(tenantId, profileId)
                .orElseThrow(() -> new EntityNotFoundException("Spending profile not found"));

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
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize spending tiers: {}", e.getMessage());
            return "[]";
        }
    }
}
