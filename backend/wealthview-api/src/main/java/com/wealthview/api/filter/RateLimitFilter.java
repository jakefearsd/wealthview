package com.wealthview.api.filter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.wealthview.api.common.ClientIpResolver;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int API_LIMIT_PER_USER = 300;
    private static final int AUTH_LIMIT_PER_IP = 60;
    private static final long WINDOW_MS = 60_000;
    /** How often expired windows are swept out of {@link #windows}. */
    private static final long SWEEP_INTERVAL_MS = 60_000;
    /**
     * Key count that forces a sweep ahead of {@link #SWEEP_INTERVAL_MS}. Without this, a burst of
     * requests from many distinct source IPs (or many distinct principals) could add unboundedly
     * many entries within a single interval before the periodic sweep gets a chance to run.
     */
    private static final int MAX_TRACKED_KEYS = 50_000;

    private final MeterRegistry meterRegistry;
    private final ClientIpResolver clientIpResolver;
    private final LongSupplier clock;
    private final ConcurrentMap<String, RateWindow> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepAt;

    // Explicit @Autowired: with the testing constructor below there are two candidates, so the
    // single-constructor implicit rule no longer applies and Spring needs to be told which to use.
    @Autowired
    public RateLimitFilter(MeterRegistry meterRegistry, ClientIpResolver clientIpResolver) {
        this(meterRegistry, clientIpResolver, System::currentTimeMillis);
    }

    /** Visible for testing: lets a test drive window expiry without sleeping through a real window. */
    RateLimitFilter(MeterRegistry meterRegistry, ClientIpResolver clientIpResolver, LongSupplier clock) {
        this.meterRegistry = meterRegistry;
        this.clientIpResolver = clientIpResolver;
        this.clock = clock;
        this.lastSweepAt = new AtomicLong(clock.getAsLong());
        meterRegistry.gauge("wealthview.ratelimit.tracked_keys", windows, Map::size);
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

        var ip = clientIpResolver.resolve(request);
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

        long now = clock.getAsLong();
        sweepExpiredWindowsIfDue(now);

        var window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.startTime > WINDOW_MS) {
                return new RateWindow(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        long resetEpochSec = (window.startTime + WINDOW_MS) / 1000L;
        int used = window.count.get();
        int remaining = Math.max(0, limit - used);
        response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(remaining));
        response.setHeader("X-RateLimit-Reset", Long.toString(resetEpochSec));

        if (used > limit) {
            log.warn("Rate limit exceeded: key={} count={} limit={}", key, used, limit);
            meterRegistry.counter("wealthview.ratelimit.exceeded", "type", isAuth ? "ip" : "user").increment();
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":"RATE_LIMITED","message":"Too many requests","status":429}""");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Drops windows that have already expired, so {@link #windows} stays bounded by the number of
     * distinct clients actually seen in the last {@link #WINDOW_MS} rather than growing for the
     * lifetime of the process. Runs at most once per {@link #SWEEP_INTERVAL_MS}, or immediately once
     * the map exceeds {@link #MAX_TRACKED_KEYS}.
     *
     * <p>Only expired entries are removed — never live ones — so a client cannot reset its own
     * budget by flooding the map with throwaway keys to trigger a sweep.
     */
    private void sweepExpiredWindowsIfDue(long now) {
        long previousSweep = lastSweepAt.get();
        boolean intervalElapsed = now - previousSweep >= SWEEP_INTERVAL_MS;
        if (!intervalElapsed && windows.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        // Single sweeper: whichever thread wins the CAS does the work, the rest carry on serving.
        if (!lastSweepAt.compareAndSet(previousSweep, now)) {
            return;
        }
        int before = windows.size();
        windows.values().removeIf(w -> now - w.startTime() > WINDOW_MS);
        int evicted = before - windows.size();
        if (evicted > 0) {
            log.debug("Swept {} expired rate-limit windows, {} still tracked", evicted, windows.size());
        }
    }

    private record RateWindow(long startTime, AtomicInteger count) {}
}
