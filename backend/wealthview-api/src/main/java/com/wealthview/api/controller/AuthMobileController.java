package com.wealthview.api.controller;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wealthview.api.common.ClientIpResolver;
import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.auth.AuthRequestContext;
import com.wealthview.core.auth.AuthService;
import com.wealthview.core.auth.dto.AuthResult;
import com.wealthview.core.auth.dto.LoginOutcome;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.MfaChallengeRequest;
import com.wealthview.core.auth.dto.MobileAuthResponse;
import com.wealthview.core.auth.dto.MobileRefreshRequest;
import com.wealthview.core.auth.dto.RegisterRequest;

/**
 * Token-in-body auth endpoints for native mobile clients.
 *
 * <p>This controller is the parallel of {@link AuthController}. The cookie
 * controller's invariant — tokens never leave the cookie boundary — exists
 * to mitigate XSS-driven token exfiltration in browser contexts.
 * Native apps don't run untrusted JavaScript, so that invariant doesn't
 * apply; instead, the contract here is:
 *
 * <ul>
 *   <li>Access and refresh tokens are returned in the response body.</li>
 *   <li>Clients are expected to persist the refresh token in OS-managed
 *       secure storage (iOS Keychain or Android Keystore-backed
 *       {@code EncryptedSharedPreferences}). Storage hygiene is the
 *       client's responsibility.</li>
 *   <li>Authenticated requests carry {@code Authorization: Bearer <jwt>}
 *       and skip CSRF (see {@code SecurityConfig}); the
 *       {@code JwtAuthenticationFilter} accepts the header as well as the
 *       cookie and shares the rest of the auth pipeline with the web
 *       client.</li>
 * </ul>
 *
 * <p>{@code GET /api/v1/auth/me} on {@link AuthController} works for both
 * transports — it reads the principal from the security context, which the
 * filter populates identically whether the token came from a cookie or the
 * Bearer header.
 */
@RestController
@RequestMapping("/api/v1/auth/token")
public class AuthMobileController {

    private static final String TRANSPORT_TAG = "bearer";

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;

    public AuthMobileController(AuthService authService,
                                ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest) {
        var ctx = buildContext(httpRequest, request.deviceLabel());
        var outcome = authService.loginInitiate(request, ctx);
        if (outcome instanceof LoginOutcome.MfaRequired m) {
            return ResponseEntity.ok(Map.of("mfa_required", true, "mfa_token", m.mfaToken()));
        }
        var tokens = ((LoginOutcome.Tokens) outcome).result();
        return ResponseEntity.ok(toResponse(tokens));
    }

    @PostMapping("/mfa/challenge")
    public ResponseEntity<MobileAuthResponse> mfaChallenge(@Valid @RequestBody MfaChallengeRequest request,
                                                           HttpServletRequest httpRequest) {
        var ctx = buildContext(httpRequest, null);
        var result = authService.completeMfaChallenge(request.mfaToken(),
                request.totpCode(), request.recoveryCode(), ctx);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/register")
    public ResponseEntity<MobileAuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                       HttpServletRequest httpRequest) {
        var ctx = buildContext(httpRequest, null);
        var result = authService.register(request, ctx);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @PostMapping("/refresh")
    public ResponseEntity<MobileAuthResponse> refresh(@Valid @RequestBody MobileRefreshRequest request,
                                                      HttpServletRequest httpRequest) {
        var ctx = buildContext(httpRequest, null);
        var result = authService.refresh(request.refreshToken(), ctx);
        return ResponseEntity.ok(toResponse(result));
    }

    private AuthRequestContext buildContext(HttpServletRequest httpRequest, String deviceLabel) {
        var ip = clientIpResolver.resolve(httpRequest);
        var ua = httpRequest.getHeader("User-Agent");
        return new AuthRequestContext(TRANSPORT_TAG, ip, ua, deviceLabel);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal TenantUserPrincipal principal) {
        authService.logout(principal.userId(), TRANSPORT_TAG);
        return ResponseEntity.noContent().build();
    }

    private MobileAuthResponse toResponse(AuthResult result) {
        return MobileAuthResponse.from(result);
    }
}
