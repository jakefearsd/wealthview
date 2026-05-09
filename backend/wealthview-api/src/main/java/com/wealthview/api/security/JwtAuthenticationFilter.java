package com.wealthview.api.security;

import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final SessionStateValidator sessionStateValidator;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   SessionStateValidator sessionStateValidator) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.sessionStateValidator = sessionStateValidator;
    }

    static final String BEARER_AUTHENTICATED_ATTRIBUTE = "BEARER_AUTHENTICATED";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var externalId = request.getHeader("X-Request-ID");
        var requestId = externalId != null
                ? externalId.substring(0, Math.min(externalId.length(), 32))
                : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MDC.put("requestId", requestId);

        try {
            var bearerToken = extractBearerToken(request);
            var token = bearerToken != null ? bearerToken : extractCookieToken(request);

            if (token != null && jwtTokenProvider.validateAccessToken(token)) {
                var userId = jwtTokenProvider.extractUserId(token);
                var generation = jwtTokenProvider.extractGeneration(token);
                var sessionId = jwtTokenProvider.extractSessionId(token);

                if (sessionStateValidator.isSessionValid(userId, generation, sessionId)) {
                    var tenantId = jwtTokenProvider.extractTenantId(token);
                    var role = jwtTokenProvider.extractRole(token);
                    var email = jwtTokenProvider.extractEmail(token);

                    MDC.put("userId", userId.toString());
                    MDC.put("tenantId", tenantId.toString());

                    if (bearerToken != null) {
                        request.setAttribute(BEARER_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
                    }

                    var principal = new TenantUserPrincipal(userId, tenantId, email, role, sessionId);
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String extractBearerToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        var token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    private String extractCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if ("access_token".equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
