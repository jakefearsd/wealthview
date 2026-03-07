package com.wealthview.core.notification;

import com.wealthview.core.notification.dto.NotificationPreferenceRequest;
import com.wealthview.core.notification.dto.NotificationPreferenceResponse;
import com.wealthview.persistence.entity.NotificationPreferenceEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.NotificationPreferenceRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NotificationPreferenceService preferenceService;

    private UUID userId;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        var tenant = new TenantEntity("Test");
        user = new UserEntity(tenant, "test@example.com", "hash", "admin");
    }

    @Test
    void getPreferences_returnsAllTypes() {
        var pref = new NotificationPreferenceEntity(user, "LARGE_TRANSACTION", true);
        when(preferenceRepository.findByUser_Id(userId)).thenReturn(List.of(pref));

        List<NotificationPreferenceResponse> result = preferenceService.getPreferences(userId);

        assertThat(result).hasSize(3);
        var largeTx = result.stream()
                .filter(r -> r.notificationType().equals("LARGE_TRANSACTION"))
                .findFirst().orElseThrow();
        assertThat(largeTx.enabled()).isTrue();
    }

    @Test
    void getPreferences_defaultsToEnabledForMissingTypes() {
        when(preferenceRepository.findByUser_Id(userId)).thenReturn(List.of());

        List<NotificationPreferenceResponse> result = preferenceService.getPreferences(userId);

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(NotificationPreferenceResponse::enabled);
    }

    @Test
    void updatePreferences_createsNewPreference() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(preferenceRepository.findByUser_IdAndNotificationType(userId, "LARGE_TRANSACTION"))
                .thenReturn(Optional.empty());
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new NotificationPreferenceRequest(List.of(
                new NotificationPreferenceRequest.PreferenceItem("LARGE_TRANSACTION", false)
        ));

        preferenceService.updatePreferences(userId, request);

        var captor = ArgumentCaptor.forClass(NotificationPreferenceEntity.class);
        verify(preferenceRepository).save(captor.capture());
        assertThat(captor.getValue().getNotificationType()).isEqualTo("LARGE_TRANSACTION");
        assertThat(captor.getValue().isEnabled()).isFalse();
    }

    @Test
    void updatePreferences_updatesExistingPreference() {
        var existing = new NotificationPreferenceEntity(user, "IMPORT_COMPLETE", true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(preferenceRepository.findByUser_IdAndNotificationType(userId, "IMPORT_COMPLETE"))
                .thenReturn(Optional.of(existing));
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new NotificationPreferenceRequest(List.of(
                new NotificationPreferenceRequest.PreferenceItem("IMPORT_COMPLETE", false)
        ));

        preferenceService.updatePreferences(userId, request);

        verify(preferenceRepository).save(existing);
        assertThat(existing.isEnabled()).isFalse();
    }
}
