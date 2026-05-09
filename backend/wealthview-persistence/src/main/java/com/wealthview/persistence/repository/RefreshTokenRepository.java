package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByJti(UUID jti);

    @Modifying
    @Query("""
            UPDATE RefreshTokenEntity r
               SET r.revokedAt = :revokedAt, r.updatedAt = :revokedAt
             WHERE r.userId = :userId
               AND r.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") UUID userId,
                         @Param("revokedAt") OffsetDateTime revokedAt);
}
