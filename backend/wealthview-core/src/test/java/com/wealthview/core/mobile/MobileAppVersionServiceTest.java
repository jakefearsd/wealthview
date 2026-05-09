package com.wealthview.core.mobile;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.mobile.dto.VersionCheckResponse;
import com.wealthview.persistence.entity.MobileAppVersionEntity;
import com.wealthview.persistence.repository.MobileAppVersionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobileAppVersionServiceTest {

    private MobileAppVersionRepository repository;
    private MobileAppVersionLookup lookup;
    private MeterRegistry meterRegistry;
    private MobileAppVersionService service;

    @BeforeEach
    void setUp() {
        repository = mock(MobileAppVersionRepository.class);
        lookup = mock(MobileAppVersionLookup.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new MobileAppVersionService(repository, lookup, meterRegistry);
    }

    @Test
    void versionCheck_currentEqualToLatest_returnsBothFalse() {
        when(lookup.findByPlatform("android"))
                .thenReturn(row("android", "1.0.0", "1.5.0"));

        var response = service.versionCheck("android", "1.5.0");

        assertThat(response.platform()).isEqualTo("android");
        assertThat(response.currentVersion()).isEqualTo("1.5.0");
        assertThat(response.minimumSupportedVersion()).isEqualTo("1.0.0");
        assertThat(response.latestVersion()).isEqualTo("1.5.0");
        assertThat(response.updateRequired()).isFalse();
        assertThat(response.updateRecommended()).isFalse();
    }

    @Test
    void versionCheck_currentBelowMinimum_returnsUpdateRequiredOnly() {
        when(lookup.findByPlatform("android"))
                .thenReturn(row("android", "1.5.0", "2.0.0"));

        var response = service.versionCheck("android", "1.0.0");

        assertThat(response.updateRequired()).isTrue();
        assertThat(response.updateRecommended()).isFalse();
    }

    @Test
    void versionCheck_currentBetweenMinAndLatest_returnsUpdateRecommended() {
        when(lookup.findByPlatform("android"))
                .thenReturn(row("android", "1.0.0", "2.0.0"));

        var response = service.versionCheck("android", "1.5.0");

        assertThat(response.updateRequired()).isFalse();
        assertThat(response.updateRecommended()).isTrue();
    }

    @Test
    void versionCheck_uppercasePlatform_normalizedToLowercase() {
        when(lookup.findByPlatform("ios"))
                .thenReturn(row("ios", "1.0.0", "1.0.0"));

        var response = service.versionCheck("IOS", "1.0.0");

        assertThat(response.platform()).isEqualTo("ios");
    }

    @Test
    void versionCheck_unknownPlatform_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.versionCheck("windows", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform");
    }

    @Test
    void versionCheck_malformedVersion_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.versionCheck("android", "not-a-version"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void versionCheck_missingRow_throwsEntityNotFound() {
        when(lookup.findByPlatform("android"))
                .thenThrow(new EntityNotFoundException("No version row configured for platform: android"));

        assertThatThrownBy(() -> service.versionCheck("android", "1.0.0"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void versionCheck_messageReturnedWhenSet() {
        var entity = row("android", "1.0.0", "1.5.0");
        entity.setMessage("Required for new tax features");
        when(lookup.findByPlatform("android")).thenReturn(entity);

        var response = service.versionCheck("android", "1.5.0");

        assertThat(response.message()).isEqualTo("Required for new tax features");
    }

    @Test
    void versionCheck_messageNullWhenUnset() {
        when(lookup.findByPlatform("android"))
                .thenReturn(row("android", "1.0.0", "1.0.0"));

        var response = service.versionCheck("android", "1.0.0");

        assertThat(response.message()).isNull();
    }

    @Test
    void versionCheck_emitsCounterWithOutcomeUpToDate() {
        when(lookup.findByPlatform("android"))
                .thenReturn(row("android", "1.0.0", "1.0.0"));

        service.versionCheck("android", "1.0.0");

        var counter = meterRegistry.find("wealthview.app.version_check_total")
                .tag("platform", "android")
                .tag("outcome", "up_to_date")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void versionCheck_emitsCounterWithOutcomeUpdateRequired() {
        when(lookup.findByPlatform("android"))
                .thenReturn(row("android", "1.5.0", "2.0.0"));

        service.versionCheck("android", "1.0.0");

        var counter = meterRegistry.find("wealthview.app.version_check_total")
                .tag("platform", "android")
                .tag("outcome", "update_required")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void versionCheck_emitsCounterWithOutcomeUpdateRecommended() {
        when(lookup.findByPlatform("android"))
                .thenReturn(row("android", "1.0.0", "2.0.0"));

        service.versionCheck("android", "1.5.0");

        var counter = meterRegistry.find("wealthview.app.version_check_total")
                .tag("platform", "android")
                .tag("outcome", "update_recommended")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void versionCheck_invalidInputEmitsCounterWithOutcomeInvalidRequest() {
        try {
            service.versionCheck("windows", "1.0.0");
        } catch (IllegalArgumentException ignored) {
            // expected
        }

        var counter = meterRegistry.find("wealthview.app.version_check_total")
                .tag("outcome", "invalid_request")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void updateVersion_persistsAllFields() {
        var existing = row("android", "0.0.1", "0.0.1");
        when(repository.findById("android")).thenReturn(Optional.of(existing));
        when(repository.save(any(MobileAppVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.updateVersion("android", "1.5.0", "2.0.0",
                "https://play.google.com/store/apps/details?id=com.wealthview",
                "Required for new tax features");

        assertThat(response.minimumSupportedVersion()).isEqualTo("1.5.0");
        assertThat(response.latestVersion()).isEqualTo("2.0.0");
        assertThat(response.message()).isEqualTo("Required for new tax features");
        verify(repository).save(existing);
    }

    @Test
    void updateVersion_unknownPlatform_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.updateVersion("windows", "1.0.0", "1.0.0",
                "https://example.com", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform");
    }

    @Test
    void updateVersion_invalidMinSemver_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.updateVersion("android", "garbage", "1.0.0",
                "https://example.com", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateVersion_invalidLatestSemver_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.updateVersion("android", "1.0.0", "garbage",
                "https://example.com", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateVersion_missingRow_throwsEntityNotFound() {
        when(repository.findById("android")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateVersion("android", "1.0.0", "1.0.0",
                "https://example.com", null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void listAll_returnsAllPlatforms() {
        when(repository.findAll()).thenReturn(List.of(
                row("android", "1.0.0", "1.5.0"),
                row("ios", "0.9.0", "1.4.0")));

        var responses = service.listAll();

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(VersionCheckResponse::platform)
                .containsExactlyInAnyOrder("android", "ios");
    }

    private MobileAppVersionEntity row(String platform, String minSupported, String latest) {
        return new MobileAppVersionEntity(platform, minSupported, latest,
                "https://example.com/app", null);
    }
}
