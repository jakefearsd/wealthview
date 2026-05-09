package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSessionEntity, UUID> {

    @Query("""
            SELECT s FROM UserSessionEntity s
             WHERE s.userId = :userId
               AND s.revokedAt IS NULL
             ORDER BY s.lastUsedAt DESC
            """)
    List<UserSessionEntity> findActiveByUserId(@Param("userId") UUID userId);

    Optional<UserSessionEntity> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("""
            UPDATE UserSessionEntity s
               SET s.revokedAt = :revokedAt
             WHERE s.userId = :userId
               AND s.id <> :keepId
               AND s.revokedAt IS NULL
            """)
    int revokeAllExcept(@Param("userId") UUID userId,
                        @Param("keepId") UUID keepId,
                        @Param("revokedAt") OffsetDateTime revokedAt);

    @Modifying
    @Query("""
            UPDATE UserSessionEntity s
               SET s.lastUsedAt = :now
             WHERE s.id = :id
               AND s.lastUsedAt < :threshold
            """)
    int touchLastUsedIfStale(@Param("id") UUID id,
                             @Param("now") OffsetDateTime now,
                             @Param("threshold") OffsetDateTime threshold);
}
