package com.wealthview.core.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.projection.dto.CreateSpendingProfileRequest;
import com.wealthview.core.projection.dto.IncomeStreamRequest;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.projection.dto.UpdateSpendingProfileRequest;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SpendingProfileService {

    private final SpendingProfileRepository profileRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpendingProfileService(SpendingProfileRepository profileRepository,
                                   TenantRepository tenantRepository) {
        this.profileRepository = profileRepository;
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
                serializeIncomeStreams(request.incomeStreams()));

        var saved = profileRepository.save(entity);
        return SpendingProfileResponse.from(saved);
    }

    @Transactional
    public SpendingProfileResponse updateProfile(UUID tenantId, UUID profileId, UpdateSpendingProfileRequest request) {
        var entity = profileRepository.findByTenant_IdAndId(tenantId, profileId)
                .orElseThrow(() -> new EntityNotFoundException("Spending profile not found"));

        entity.setName(request.name());
        entity.setEssentialExpenses(request.essentialExpenses());
        entity.setDiscretionaryExpenses(request.discretionaryExpenses());
        entity.setIncomeStreams(serializeIncomeStreams(request.incomeStreams()));
        entity.setUpdatedAt(OffsetDateTime.now());

        var saved = profileRepository.save(entity);
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
        profileRepository.delete(entity);
    }

    private String serializeIncomeStreams(List<IncomeStreamRequest> streams) {
        if (streams == null || streams.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(streams);
        } catch (Exception e) {
            return "[]";
        }
    }
}
