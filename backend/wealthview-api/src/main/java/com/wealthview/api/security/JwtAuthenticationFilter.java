package com.wealthview.api.security;

import com.wealthview.core.auth.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

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
            var token = extractToken(request);

            if (token != null && jwtTokenProvider.validateAccessToken(token)) {
                var userId = jwtTokenProvider.extractUserId(token);
                var tenantId = jwtTokenProvider.extractTenantId(token);
                var role = jwtTokenProvider.extractRole(token);
                var email = jwtTokenProvider.extractEmail(token);

                MDC.put("userId", userId.toString());
                MDC.put("tenantId", tenantId.toString());

                var principal = new TenantUserPrincipal(userId, tenantId, email, role);
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
