package com.wealthview.core.auth;

import com.wealthview.persistence.repository.UserRepository;
import com.wealthview.persistence.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class SessionStateValidator {

    private static final Logger log = LoggerFactory.getLogger(SessionStateValidator.class);

    /** Skip last_used_at writes if the row was touched within this many seconds. */
    private static final long LAST_USED_THROTTLE_SECONDS = 60;

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;

    public SessionStateValidator(UserRepository userRepository,
                                 UserSessionRepository userSessionRepository) {
        this.userRepository = userRepository;
        this.userSessionRepository = userSessionRepository;
    }

    @Transactional
    public boolean isSessionValid(UUID userId, int tokenGeneration) {
        return isSessionValid(userId, tokenGeneration, null);
    }

    @Transactional
    public boolean isSessionValid(UUID userId, int tokenGeneration, UUID sessionId) {
        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Session rejected: user {} not found", userId);
            return false;
        }
        if (!user.isActive()) {
            log.warn("Session rejected: user {} is disabled", userId);
            return false;
        }
        if (!user.getTenant().isActive()) {
            log.warn("Session rejected: tenant {} is disabled (user {})",
                    user.getTenantId(), userId);
            return false;
        }
        if (tokenGeneration != user.getTokenGeneration()) {
            log.warn("Session rejected: stale generation for user {} (token={}, current={})",
                    userId, tokenGeneration, user.getTokenGeneration());
            return false;
        }
        if (sessionId != null) {
            var session = userSessionRepository.findByIdAndUserId(sessionId, userId).orElse(null);
            if (session == null) {
                log.warn("Session rejected: sid {} not found for user {}", sessionId, userId);
                return false;
            }
            if (session.getRevokedAt() != null) {
                log.warn("Session rejected: sid {} revoked for user {}", sessionId, userId);
                return false;
            }
            var now = OffsetDateTime.now();
            var threshold = now.minusSeconds(LAST_USED_THROTTLE_SECONDS);
            // Throttled write: only updates the row if last_used_at is older
            // than the threshold. Avoids one write per request on hot sessions.
            userSessionRepository.touchLastUsedIfStale(sessionId, now, threshold);
        }
        return true;
    }
}
