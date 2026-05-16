package com.wealthview.core.mobile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.mobile.dto.VersionCheckResponse;
import com.wealthview.persistence.entity.MobileAppVersionEntity;
import com.wealthview.persistence.repository.MobileAppVersionRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Backs the mobile force-update endpoint. Reads/writes the global
 * {@code mobile_app_versions} table (one row per platform).
 *
 * <p>The version-check path delegates to {@link MobileAppVersionLookup} for
 * the cached repository read (5-minute TTL by platform key — see
 * {@link com.wealthview.core.config.CacheConfig}). Admin updates evict the
 * entry so a freshly-bumped minimum-version takes effect at most one cache
 * miss later.
 */
@Service
public class MobileAppVersionService {

    private static final Logger log = LoggerFactory.getLogger(MobileAppVersionService.class);
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of("android", "ios");
    private static final String COUNTER_NAME = "wealthview.app.version_check_total";

    private final MobileAppVersionRepository repository;
    private final MobileAppVersionLookup lookup;
    private final MeterRegistry meterRegistry;

    public MobileAppVersionService(MobileAppVersionRepository repository,
                                   MobileAppVersionLookup lookup,
                                   MeterRegistry meterRegistry) {
        this.repository = repository;
        this.lookup = lookup;
        this.meterRegistry = meterRegistry;
    }

    public VersionCheckResponse versionCheck(String platform, String currentVersion) {
        String normalized;
        try {
            normalized = requireSupportedPlatform(platform);
            requireValidSemver(currentVersion, "version");
        } catch (IllegalArgumentException ex) {
            meterRegistry.counter(COUNTER_NAME,
                    "platform", platform == null ? "unknown" : platform.toLowerCase(Locale.ROOT),
                    "outcome", "invalid_request").increment();
            throw ex;
        }

        var entity = lookup.findByPlatform(normalized);

        boolean updateRequired = Semver.compare(currentVersion, entity.getMinimumSupportedVersion()) < 0;
        boolean updateRecommended = !updateRequired
                && Semver.compare(currentVersion, entity.getLatestVersion()) < 0;

        String outcome;
        if (updateRequired) {
            outcome = "update_required";
        } else if (updateRecommended) {
            outcome = "update_recommended";
        } else {
            outcome = "up_to_date";
        }

        meterRegistry.counter(COUNTER_NAME, "platform", normalized, "outcome", outcome).increment();

        return new VersionCheckResponse(
                normalized,
                currentVersion,
                entity.getMinimumSupportedVersion(),
                entity.getLatestVersion(),
                updateRequired,
                updateRecommended,
                entity.getStoreUrl(),
                entity.getMessage());
    }

    @Transactional(readOnly = true)
    public List<VersionCheckResponse> listAll() {
        return repository.findAll().stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @CacheEvict(value = "mobileAppVersions", key = "#platform.toLowerCase()")
    @Transactional
    public VersionCheckResponse updateVersion(String platform, String minimumSupportedVersion,
                                              String latestVersion, String storeUrl, String message) {
        var normalized = requireSupportedPlatform(platform);
        requireValidSemver(minimumSupportedVersion, "minimum_supported_version");
        requireValidSemver(latestVersion, "latest_version");
        if (storeUrl == null || storeUrl.isBlank()) {
            throw new IllegalArgumentException("store_url must not be blank");
        }

        var entity = repository.findById(normalized)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No version row configured for platform: " + normalized));
        entity.setMinimumSupportedVersion(minimumSupportedVersion);
        entity.setLatestVersion(latestVersion);
        entity.setStoreUrl(storeUrl);
        entity.setMessage(message);
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);
        log.info("Mobile app version updated: platform={} min={} latest={}",
                normalized, minimumSupportedVersion, latestVersion);
        return toAdminResponse(entity);
    }

    private VersionCheckResponse toAdminResponse(MobileAppVersionEntity entity) {
        return new VersionCheckResponse(
                entity.getPlatform(),
                null,
                entity.getMinimumSupportedVersion(),
                entity.getLatestVersion(),
                false,
                false,
                entity.getStoreUrl(),
                entity.getMessage());
    }

    private String requireSupportedPlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform must not be blank");
        }
        var normalized = platform.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PLATFORMS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Unknown platform: " + platform + " (supported: android, ios)");
        }
        return normalized;
    }

    private void requireValidSemver(String version, String field) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!Semver.isValid(version)) {
            throw new IllegalArgumentException("Invalid " + field + ": " + version);
        }
    }
}
