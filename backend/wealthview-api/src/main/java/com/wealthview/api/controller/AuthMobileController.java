package com.wealthview.api.controller;

import com.wealthview.api.common.ClientIpResolver;
import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.auth.AuthService;
import com.wealthview.core.auth.dto.AuthResult;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.MobileAuthResponse;
import com.wealthview.core.auth.dto.MobileRefreshRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<MobileAuthResponse> login(@Valid @RequestBody LoginRequest request,
                                                    HttpServletRequest httpRequest) {
        var ipAddress = clientIpResolver.resolve(httpRequest);
        var result = authService.login(request, ipAddress, TRANSPORT_TAG);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/register")
    public ResponseEntity<MobileAuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        var result = authService.register(request, TRANSPORT_TAG);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @PostMapping("/refresh")
    public ResponseEntity<MobileAuthResponse> refresh(@Valid @RequestBody MobileRefreshRequest request) {
        var result = authService.refresh(request.refreshToken(), TRANSPORT_TAG);
        return ResponseEntity.ok(toResponse(result));
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
