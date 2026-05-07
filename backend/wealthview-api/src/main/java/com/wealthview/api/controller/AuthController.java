package com.wealthview.api.controller;

import com.wealthview.api.common.ClientIpResolver;
import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.auth.AuthService;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.dto.AuthIdentityResponse;
import com.wealthview.core.auth.dto.AuthResult;
import com.wealthview.core.auth.dto.CurrentUserResponse;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Auth endpoints. Tokens are issued and consumed exclusively as
 * {@code HttpOnly; Secure; SameSite=Strict} cookies — they are never present
 * in a request body or response body. This removes the XSS-driven
 * token-exfiltration class of risk that came with localStorage storage.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String ACCESS_TOKEN_COOKIE = "access_token";
    static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;
    private final JwtTokenProvider jwtTokenProvider;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                          ClientIpResolver clientIpResolver,
                          JwtTokenProvider jwtTokenProvider,
                          @Value("${app.cookie.secure:true}") boolean cookieSecure) {
        this.authService = authService;
        this.clientIpResolver = clientIpResolver;
        this.jwtTokenProvider = jwtTokenProvider;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthIdentityResponse> login(@Valid @RequestBody LoginRequest request,
                                                      HttpServletRequest httpRequest) {
        var ipAddress = clientIpResolver.resolve(httpRequest);
        return respondWithCookies(authService.login(request, ipAddress), HttpStatus.OK);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthIdentityResponse> register(@Valid @RequestBody RegisterRequest request) {
        return respondWithCookies(authService.register(request), HttpStatus.CREATED);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthIdentityResponse> refresh(HttpServletRequest httpRequest) {
        var refreshToken = readCookie(httpRequest, REFRESH_TOKEN_COOKIE);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadCredentialsException("Missing refresh token cookie");
        }
        return respondWithCookies(authService.refresh(refreshToken), HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal TenantUserPrincipal principal) {
        authService.logout(principal.userId());
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, clearCookie(ACCESS_TOKEN_COOKIE).toString());
        headers.add(HttpHeaders.SET_COOKIE, clearCookie(REFRESH_TOKEN_COOKIE).toString());
        return ResponseEntity.noContent().headers(headers).build();
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(new CurrentUserResponse(
                principal.userId(),
                principal.tenantId(),
                principal.email(),
                principal.role()));
    }

    private ResponseEntity<AuthIdentityResponse> respondWithCookies(AuthResult result, HttpStatus status) {
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE,
                buildCookie(ACCESS_TOKEN_COOKIE, result.accessToken(),
                        jwtTokenProvider.getAccessTokenExpirationMs()).toString());
        headers.add(HttpHeaders.SET_COOKIE,
                buildCookie(REFRESH_TOKEN_COOKIE, result.refreshToken(),
                        jwtTokenProvider.getRefreshTokenExpirationMs()).toString());
        return ResponseEntity.status(status).headers(headers).body(result.identity());
    }

    private ResponseCookie buildCookie(String name, String value, long maxAgeMs) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofMillis(maxAgeMs))
                .build();
    }

    private ResponseCookie clearCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
