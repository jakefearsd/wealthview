package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.MfaChallengeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MfaChallengeRepository extends JpaRepository<MfaChallengeEntity, UUID> {

    Optional<MfaChallengeEntity> findByJti(UUID jti);
}
