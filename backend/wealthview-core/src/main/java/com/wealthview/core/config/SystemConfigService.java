package com.wealthview.core.config;

import com.wealthview.core.config.dto.SystemConfigResponse;
import com.wealthview.persistence.entity.SystemConfigEntity;
import com.wealthview.persistence.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);
    private static final Set<String> SENSITIVE_KEYS = Set.of("finnhub.api-key", "jwt.secret");

    private final SystemConfigRepository systemConfigRepository;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public SystemConfigService(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
    }

    @Transactional(readOnly = true)
    public List<SystemConfigResponse> getAll() {
        return systemConfigRepository.findAll().stream()
                .map(entity -> {
                    if (SENSITIVE_KEYS.contains(entity.getKey())) {
                        return SystemConfigResponse.masked(entity, maskValue(entity.getValue()));
                    }
                    return SystemConfigResponse.from(entity);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public String get(String key) {
        var cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        var value = systemConfigRepository.findById(key)
                .map(SystemConfigEntity::getValue)
                .orElse(null);

        if (value != null) {
            cache.put(key, value);
        }
        return value;
    }

    @Transactional
    public void set(String key, String value) {
        var entity = systemConfigRepository.findById(key)
                .orElse(new SystemConfigEntity(key, value));
        entity.setValue(value);
        entity.setUpdatedAt(OffsetDateTime.now());
        systemConfigRepository.save(entity);
        cache.put(key, value);
        log.info("System config updated: {}", key);
    }

    @Transactional
    public void seedDefaults(Map<String, String> defaults) {
        for (var entry : defaults.entrySet()) {
            if (!systemConfigRepository.existsById(entry.getKey())) {
                systemConfigRepository.save(new SystemConfigEntity(entry.getKey(), entry.getValue()));
                cache.put(entry.getKey(), entry.getValue());
                log.info("Seeded default config: {}", entry.getKey());
            }
        }
    }

    static String maskValue(String value) {
        if (value == null || value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
