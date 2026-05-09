package com.wealthview.core.auth;

/**
 * Per-request authentication context: transport (cookie or bearer), the
 * caller's IP, optional User-Agent header, and an optional device label
 * supplied in the login body. Carried as a single value so AuthService
 * methods don't grow a 6-arg parameter list, and so adding new
 * request-scoped facts (geo, fingerprint, etc.) doesn't ripple downstream.
 */
public record AuthRequestContext(
        String transport,
        String ipAddress,
        String userAgent,
        String deviceLabel
) {
    public static final String COOKIE = "cookie";
    public static final String BEARER = "bearer";

    public static AuthRequestContext cookie(String ipAddress) {
        return new AuthRequestContext(COOKIE, ipAddress, null, null);
    }

    public static AuthRequestContext bearer(String ipAddress) {
        return new AuthRequestContext(BEARER, ipAddress, null, null);
    }

    public AuthRequestContext withUserAgent(String userAgent) {
        return new AuthRequestContext(transport, ipAddress, userAgent, deviceLabel);
    }

    public AuthRequestContext withDeviceLabel(String deviceLabel) {
        return new AuthRequestContext(transport, ipAddress, userAgent, deviceLabel);
    }
}
