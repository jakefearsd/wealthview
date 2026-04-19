package com.wealthview.api.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void resolve_noTrustedProxies_returnsRemoteAddr() {
        var resolver = new ClientIpResolver(List.of());
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void resolve_remoteAddrNotInTrustedProxies_ignoresForwardedHeader() {
        var resolver = new ClientIpResolver(List.of("10.0.0.1"));
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void resolve_remoteAddrIsTrustedProxy_returnsFirstForwardedAddress() {
        var resolver = new ClientIpResolver(List.of("10.0.0.1"));
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1, 10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
    }

    @Test
    void resolve_trustedProxyButMissingHeader_returnsRemoteAddr() {
        var resolver = new ClientIpResolver(List.of("10.0.0.1"));
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void resolve_trustedProxyWithEmptyHeader_returnsRemoteAddr() {
        var resolver = new ClientIpResolver(List.of("10.0.0.1"));
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }
}
