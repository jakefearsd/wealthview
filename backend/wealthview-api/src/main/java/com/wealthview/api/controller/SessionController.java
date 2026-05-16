package com.wealthview.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.auth.SessionService;
import com.wealthview.core.auth.dto.SessionResponse;

/**
 * Per-device session management. Listed sessions are non-revoked rows from
 * {@code user_sessions}, sorted by last_used_at descending. The endpoint
 * works for both cookie and Bearer transports — authentication is whatever
 * the {@code JwtAuthenticationFilter} accepts.
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> list(@AuthenticationPrincipal TenantUserPrincipal principal) {
        var sessions = sessionService.listForUser(principal.userId(), principal.sessionId());
        return ResponseEntity.ok(sessions);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal TenantUserPrincipal principal,
                                       @PathVariable("id") UUID id) {
        var revoked = sessionService.revoke(principal.userId(), id);
        if (!revoked) {
            // Return 404 (not 403) when the session belongs to a different
            // user. 403 would confirm the id exists somewhere in the system,
            // letting an attacker enumerate sessions across tenants.
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> revokeAllOther(@AuthenticationPrincipal TenantUserPrincipal principal) {
        sessionService.revokeAllOther(principal.userId(), principal.sessionId());
        return ResponseEntity.noContent().build();
    }
}
