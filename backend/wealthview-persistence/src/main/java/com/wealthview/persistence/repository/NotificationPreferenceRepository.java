package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.NotificationPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreferenceEntity, UUID> {

    List<NotificationPreferenceEntity> findByUser_Id(UUID userId);

    Optional<NotificationPreferenceEntity> findByUser_IdAndNotificationType(UUID userId, String notificationType);
}
