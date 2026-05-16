package com.wealthview.core.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.persistence.entity.UserSessionEntity;
import com.wealthview.persistence.repository.UserSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private UserSessionRepository sessionRepository;

    private SessionService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SessionService(sessionRepository, new SimpleMeterRegistry());
    }

    private UserSessionEntity session(UUID id) {
        var s = new UserSessionEntity(tenantId, userId, "Laptop", "cookie", "127.0.0.1", "agent");
        TestEntityHelper.setId(s, id);
        return s;
    }

    @Test
    void listForUser_marksTheCurrentSession() {
        // The session whose id matches currentSessionId must come back
        // current=true; all others current=false.
        var currentId = UUID.randomUUID();
        var otherId = UUID.randomUUID();
        when(sessionRepository.findActiveByUserId(userId))
                .thenReturn(List.of(session(currentId), session(otherId)));

        var result = service.listForUser(userId, currentId);

        assertThat(result).hasSize(2);
        assertThat(result).filteredOn(r -> r.current()).hasSize(1);
        assertThat(result).filteredOn(r -> r.id().equals(currentId)).first()
                .matches(r -> r.current(), "current session flagged");
        assertThat(result).filteredOn(r -> r.id().equals(otherId)).first()
                .matches(r -> !r.current(), "other session not flagged");
    }

    @Test
    void revoke_sessionNotOwnedByUser_returnsFalse() {
        // A session id that does not belong to the caller must return false so
        // the controller maps it to 404 — no cross-tenant existence oracle.
        var sessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());

        var revoked = service.revoke(userId, sessionId);

        assertThat(revoked).isFalse();
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void revoke_ownedActiveSession_setsRevokedAtAndReturnsTrue() {
        var sessionId = UUID.randomUUID();
        var session = session(sessionId);
        when(sessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));

        var revoked = service.revoke(userId, sessionId);

        assertThat(revoked).isTrue();
        assertThat(session.getRevokedAt()).isNotNull();
        verify(sessionRepository).save(session);
    }

    @Test
    void revoke_alreadyRevokedSession_returnsTrueWithoutResaving() {
        // Revoking an already-revoked session is idempotent: it returns true
        // but must not re-stamp revokedAt nor save again.
        var sessionId = UUID.randomUUID();
        var session = session(sessionId);
        var originalRevokedAt = OffsetDateTime.now().minusDays(1);
        session.setRevokedAt(originalRevokedAt);
        when(sessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));

        var revoked = service.revoke(userId, sessionId);

        assertThat(revoked).isTrue();
        assertThat(session.getRevokedAt()).isEqualTo(originalRevokedAt);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void revokeAllOther_withKeepSessionId_passesItThroughAndReturnsCount() {
        var keepId = UUID.randomUUID();
        when(sessionRepository.revokeAllExcept(eq(userId), eq(keepId), any())).thenReturn(3);

        var count = service.revokeAllOther(userId, keepId);

        assertThat(count).isEqualTo(3);
        verify(sessionRepository).revokeAllExcept(eq(userId), eq(keepId), any());
    }

    @Test
    void revokeAllOther_withNullKeepSessionId_revokesEverythingViaSentinelId() {
        // When the access token carries no sid, revokeAllOther must use the
        // all-zero sentinel UUID so that no real session is accidentally kept.
        var sentinel = UUID.fromString("00000000-0000-0000-0000-000000000000");
        when(sessionRepository.revokeAllExcept(eq(userId), eq(sentinel), any())).thenReturn(5);

        var count = service.revokeAllOther(userId, null);

        assertThat(count).isEqualTo(5);
        verify(sessionRepository).revokeAllExcept(eq(userId), eq(sentinel), any());
    }
}
