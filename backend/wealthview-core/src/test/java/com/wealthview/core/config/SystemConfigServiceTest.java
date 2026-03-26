package com.wealthview.core.config;

import com.wealthview.persistence.entity.SystemConfigEntity;
import com.wealthview.persistence.repository.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemConfigServiceTest {

    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private SystemConfigService service;

    @Test
    void getAll_returnsMaskedSensitiveKeys() {
        var apiKeyEntity = new SystemConfigEntity("finnhub.api-key", "sk-1234567890abcdef");
        var normalEntity = new SystemConfigEntity("app.name", "WealthView");

        when(systemConfigRepository.findAll()).thenReturn(List.of(apiKeyEntity, normalEntity));

        var result = service.getAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).key()).isEqualTo("finnhub.api-key");
        assertThat(result.get(0).value()).isEqualTo("sk-1****cdef");
        assertThat(result.get(1).key()).isEqualTo("app.name");
        assertThat(result.get(1).value()).isEqualTo("WealthView");
    }

    @Test
    void getAll_masksJwtSecret() {
        var jwtEntity = new SystemConfigEntity("jwt.secret", "my-super-secret-key-12345");

        when(systemConfigRepository.findAll()).thenReturn(List.of(jwtEntity));

        var result = service.getAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isEqualTo("my-s****2345");
    }

    @Test
    void getAll_masksShortSensitiveValue() {
        var entity = new SystemConfigEntity("finnhub.api-key", "short");

        when(systemConfigRepository.findAll()).thenReturn(List.of(entity));

        var result = service.getAll();

        assertThat(result.get(0).value()).isEqualTo("****");
    }

    @Test
    void get_returnsValueFromDb_whenNotCached() {
        var entity = new SystemConfigEntity("some.key", "some-value");
        when(systemConfigRepository.findById("some.key")).thenReturn(Optional.of(entity));

        var result = service.get("some.key");

        assertThat(result).isEqualTo("some-value");
    }

    @Test
    void get_returnsFromCache_afterFirstCall() {
        var entity = new SystemConfigEntity("cached.key", "cached-value");
        when(systemConfigRepository.findById("cached.key")).thenReturn(Optional.of(entity));

        service.get("cached.key");
        var result = service.get("cached.key");

        assertThat(result).isEqualTo("cached-value");
        verify(systemConfigRepository).findById("cached.key");
    }

    @Test
    void get_returnsNull_whenKeyNotFound() {
        when(systemConfigRepository.findById("missing")).thenReturn(Optional.empty());

        var result = service.get("missing");

        assertThat(result).isNull();
    }

    @Test
    void set_updatesExistingKey() {
        var entity = new SystemConfigEntity("existing.key", "old-value");
        when(systemConfigRepository.findById("existing.key")).thenReturn(Optional.of(entity));
        when(systemConfigRepository.save(any(SystemConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.set("existing.key", "new-value");

        assertThat(entity.getValue()).isEqualTo("new-value");
        verify(systemConfigRepository).save(entity);
    }

    @Test
    void set_createsNewKey() {
        when(systemConfigRepository.findById("new.key")).thenReturn(Optional.empty());
        when(systemConfigRepository.save(any(SystemConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.set("new.key", "new-value");

        verify(systemConfigRepository).save(any(SystemConfigEntity.class));
    }

    @Test
    void set_updatesCache() {
        when(systemConfigRepository.findById("cache.test")).thenReturn(Optional.empty());
        when(systemConfigRepository.save(any(SystemConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.set("cache.test", "cached");

        // After set(), the value should be in cache, so get() should not call findById again
        var result = service.get("cache.test");
        assertThat(result).isEqualTo("cached");
        // findById was called once by set(), but get() should NOT call it again
        verify(systemConfigRepository).findById("cache.test");
    }

    @Test
    void seedDefaults_insertsOnlyMissingKeys() {
        when(systemConfigRepository.existsById("existing")).thenReturn(true);
        when(systemConfigRepository.existsById("new")).thenReturn(false);
        when(systemConfigRepository.save(any(SystemConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.seedDefaults(Map.of("existing", "val1", "new", "val2"));

        verify(systemConfigRepository).save(any(SystemConfigEntity.class));
    }

    @Test
    void maskValue_shortValue_returnsMaskOnly() {
        assertThat(SystemConfigService.maskValue("short")).isEqualTo("****");
        assertThat(SystemConfigService.maskValue("12345678")).isEqualTo("****");
    }

    @Test
    void maskValue_longValue_showsFirstAndLastFour() {
        assertThat(SystemConfigService.maskValue("abcdefghijklm")).isEqualTo("abcd****jklm");
    }

    @Test
    void maskValue_null_returnsMaskOnly() {
        assertThat(SystemConfigService.maskValue(null)).isEqualTo("****");
    }
}
