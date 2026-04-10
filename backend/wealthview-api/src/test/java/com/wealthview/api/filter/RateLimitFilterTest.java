package com.wealthview.api.filter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private SimpleMeterRegistry meterRegistry;
    private RateLimitFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new RateLimitFilter(meterRegistry, List.of());
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonApiPath_passesThrough() throws ServletException, IOException {
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void superAdmin_bypassesRateLimit() throws ServletException, IOException {
        var auth = new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void apiRequest_withinLimit_passesThrough() throws ServletException, IOException {
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.setRemoteAddr("10.0.0.1");
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void apiRequest_exceedsUserLimit_returns429AndRecordsMetric() throws ServletException, IOException {
        for (int i = 0; i <= 300; i++) {
            var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
            request.setRemoteAddr("10.0.0.1");
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
        }

        // 301st request should be rate limited
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.setRemoteAddr("10.0.0.1");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");

        var counter = meterRegistry.find("wealthview.ratelimit.exceeded")
                .tag("type", "user").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void authRequest_exceedsIpLimit_returns429WithIpType() throws ServletException, IOException {
        for (int i = 0; i <= 60; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("192.168.1.1");
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
        }

        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.168.1.1");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);

        var counter = meterRegistry.find("wealthview.ratelimit.exceeded")
                .tag("type", "ip").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void authenticatedUser_usesNameAsKey() throws ServletException, IOException {
        var auth = new UsernamePasswordAuthenticationToken("user@test.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.setRemoteAddr("10.0.0.1");
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void untrustedProxy_xForwardedForIsIgnored_cannotBypassRateLimit() throws ServletException, IOException {
        // Attacker rotates X-Forwarded-For on every request while all requests
        // actually arrive from the same remote address. Because no trusted
        // proxy is configured, X-Forwarded-For must be ignored and the limit
        // must be enforced against the real remote address.
        for (int i = 0; i <= 60; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("198.51.100.7");
            request.addHeader("X-Forwarded-For", "203.0.113." + i);
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
        }

        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.999");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void trustedProxy_xForwardedForIsHonored() throws ServletException, IOException {
        var trustingFilter = new RateLimitFilter(meterRegistry, List.of("10.0.0.100"));

        // 61 requests with the same client IP behind a trusted proxy should exceed
        // the 60-per-IP auth limit even though remoteAddr is the proxy.
        for (int i = 0; i <= 60; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("10.0.0.100");
            request.addHeader("X-Forwarded-For", "203.0.113.50");
            var response = new MockHttpServletResponse();
            trustingFilter.doFilterInternal(request, response, filterChain);
        }

        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.100");
        request.addHeader("X-Forwarded-For", "203.0.113.50");
        var response = new MockHttpServletResponse();
        trustingFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void trustedProxy_differentClientIpsNotLimitedTogether() throws ServletException, IOException {
        var trustingFilter = new RateLimitFilter(meterRegistry, List.of("10.0.0.100"));

        // Two distinct clients behind the same trusted proxy should each get their own budget.
        for (int i = 0; i < 30; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("10.0.0.100");
            request.addHeader("X-Forwarded-For", "203.0.113.50");
            trustingFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        }
        for (int i = 0; i < 30; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("10.0.0.100");
            request.addHeader("X-Forwarded-For", "198.51.100.22");
            trustingFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        }

        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.100");
        request.addHeader("X-Forwarded-For", "198.51.100.22");
        var response = new MockHttpServletResponse();
        trustingFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void getClientIp_withoutXForwardedFor_usesRemoteAddr() throws ServletException, IOException {
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.setRemoteAddr("10.0.0.99");
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void rateLimitExceeded_responseBodyIsJson() throws ServletException, IOException {
        for (int i = 0; i <= 300; i++) {
            var req = new MockHttpServletRequest("GET", "/api/v1/test");
            req.setRemoteAddr("1.2.3.4");
            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);
        }

        var request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.setRemoteAddr("1.2.3.4");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("\"status\":429");
    }
}
