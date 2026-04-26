package com.wealthview.core.notification;

import com.wealthview.core.common.Entities;
import com.wealthview.core.notification.dto.NotificationPreferenceRequest;
import com.wealthview.core.notification.dto.NotificationPreferenceResponse;
import com.wealthview.persistence.entity.NotificationPreferenceEntity;
import com.wealthview.persistence.repository.NotificationPreferenceRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationPreferenceService {

    static final List<String> NOTIFICATION_TYPES = List.of(
            "LARGE_TRANSACTION", "IMPORT_COMPLETE", "IMPORT_FAILED"
    );

    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    public NotificationPreferenceService(NotificationPreferenceRepository preferenceRepository,
                                          UserRepository userRepository) {
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> getPreferences(UUID userId) {
        Map<String, Boolean> saved = preferenceRepository.findByUser_Id(userId).stream()
                .collect(Collectors.toMap(
                        NotificationPreferenceEntity::getNotificationType,
                        NotificationPreferenceEntity::isEnabled
                ));

        return NOTIFICATION_TYPES.stream()
                .map(type -> new NotificationPreferenceResponse(type, saved.getOrDefault(type, true)))
                .toList();
    }

    @Transactional
    public void updatePreferences(UUID userId, NotificationPreferenceRequest request) {
        var user = userRepository.findById(userId)
                .orElseThrow(Entities.notFound("User"));

        for (var item : request.preferences()) {
            var existing = preferenceRepository.findByUser_IdAndNotificationType(userId, item.notificationType());
            if (existing.isPresent()) {
                existing.get().setEnabled(item.enabled());
                preferenceRepository.save(existing.get());
            } else {
                preferenceRepository.save(new NotificationPreferenceEntity(user, item.notificationType(), item.enabled()));
            }
        }
    }
}
