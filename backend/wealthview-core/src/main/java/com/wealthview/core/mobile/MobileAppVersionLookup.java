package com.wealthview.core.mobile;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.MobileAppVersionEntity;
import com.wealthview.persistence.repository.MobileAppVersionRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached read of the {@code mobile_app_versions} row for a single platform.
 *
 * <p>Lives in its own bean so {@link MobileAppVersionService}'s call goes
 * through the Spring proxy (self-invocation inside the same bean would
 * bypass {@code @Cacheable}). The cache TTL is 5 minutes (see
 * {@link com.wealthview.core.config.CacheConfig}); admin updates evict by
 * platform key.
 */
@Component
public class MobileAppVersionLookup {

    private final MobileAppVersionRepository repository;

    public MobileAppVersionLookup(MobileAppVersionRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = "mobileAppVersions", key = "#platform")
    @Transactional(readOnly = true)
    public MobileAppVersionEntity findByPlatform(String platform) {
        return repository.findById(platform)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No version row configured for platform: " + platform));
    }
}
