package com.wealthview.api.filter;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int API_LIMIT_PER_USER = 300;
    private static final int AUTH_LIMIT_PER_IP = 60;
    private static final long WINDOW_MS = 60_000;

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, RateWindow> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        var path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))) {
            filterChain.doFilter(request, response);
            return;
        }

        var ip = getClientIp(request);
        var isAuth = path.startsWith("/api/v1/auth/");

        String key;
        int limit;
        if (isAuth) {
            key = ip + ":auth";
            limit = AUTH_LIMIT_PER_IP;
        } else {
            var principal = (auth != null && auth.isAuthenticated()) ? auth.getName() : ip;
            key = principal + ":api";
            limit = API_LIMIT_PER_USER;
        }

        var window = windows.compute(key, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.startTime > WINDOW_MS) {
                return new RateWindow(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (window.count.get() > limit) {
            log.warn("Rate limit exceeded: key={} count={} limit={}", key, window.count.get(), limit);
            meterRegistry.counter("wealthview.ratelimit.exceeded", "type", isAuth ? "ip" : "user").increment();
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":"RATE_LIMITED","message":"Too many requests","status":429}""");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private record RateWindow(long startTime, AtomicInteger count) {}
}
