package com.wealthview.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.MfaChallengeEntity;

public interface MfaChallengeRepository extends JpaRepository<MfaChallengeEntity, UUID> {

    Optional<MfaChallengeEntity> findByJti(UUID jti);
}
