package com.wealthview.core.auth;

import com.wealthview.core.auth.dto.SessionResponse;
import com.wealthview.persistence.repository.UserSessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final UserSessionRepository sessionRepository;
    private final MeterRegistry meterRegistry;

    public SessionService(UserSessionRepository sessionRepository, MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listForUser(UUID userId, UUID currentSessionId) {
        return sessionRepository.findActiveByUserId(userId).stream()
                .map(s -> SessionResponse.from(s, currentSessionId))
                .toList();
    }

    /**
     * Revoke a specific session by id. If the session does not belong to the
     * caller, returns false (the controller maps that to 404 to avoid an
     * existence oracle for cross-tenant ids).
     */
    @Transactional
    public boolean revoke(UUID userId, UUID sessionId) {
        var session = sessionRepository.findByIdAndUserId(sessionId, userId).orElse(null);
        if (session == null) {
            return false;
        }
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(OffsetDateTime.now());
            sessionRepository.save(session);
            meterRegistry.counter("wealthview.auth.session_revoked", "scope", "single").increment();
            log.info("Session {} revoked for user {}", sessionId, userId);
        }
        return true;
    }

    /**
     * Revoke every active session for the user EXCEPT the caller's current one.
     * Used by "log out everywhere else" in the UI.
     */
    @Transactional
    public int revokeAllOther(UUID userId, UUID keepSessionId) {
        // keepSessionId may be null if the access token didn't carry a sid;
        // pass a never-matching UUID to revoke everything in that case.
        var keep = keepSessionId != null ? keepSessionId
                : UUID.fromString("00000000-0000-0000-0000-000000000000");
        var count = sessionRepository.revokeAllExcept(userId, keep, OffsetDateTime.now());
        meterRegistry.counter("wealthview.auth.session_revoked", "scope", "all_other")
                .increment(count);
        log.info("{} sessions revoked for user {} (keeping {})", count, userId, keep);
        return count;
    }
}
