import { createApiClient } from '@wealthview/shared';

/**
 * Axios client for authenticated API calls — a thin cookie-transport wrapper
 * around the shared {@link createApiClient} factory (also used by mobile).
 *
 * <p>Auth tokens live in HttpOnly cookies set by the backend on login/refresh —
 * never in localStorage, never readable by JavaScript. {@code withCredentials}
 * (set by the cookie transport) tells the browser to send those cookies with
 * every request to {@code /api/v1}.
 *
 * <p>CSRF protection works automatically: axios reads the {@code XSRF-TOKEN}
 * cookie (which is NOT HttpOnly) and echoes it as the {@code X-XSRF-TOKEN}
 * header on every request. The backend's CSRF filter rejects mutations whose
 * header value doesn't match the cookie — the standard double-submit pattern.
 *
 * <p>401 handling lives in the shared client: a 401 triggers a single
 * coalesced {@code /auth/refresh} call (concurrent 401s wait on the same
 * in-flight refresh) and the original request is replayed once. URLs in
 * {@code skipRefreshPaths} are exempt — refreshing them would be circular
 * (refresh-on-refresh loop) or pointless (a failed login won't be saved by
 * retrying with a refresh token). {@code /auth/me} is intentionally NOT in
 * that list: a 401 there should fire one refresh attempt to handle the
 * "access token rotated mid-session" case before we conclude "logged out."
 */
function isOnLoginPage(): boolean {
    if (typeof window === 'undefined') return false;
    return window.location.pathname === '/login' || window.location.pathname === '/register';
}

const client = createApiClient({
    baseURL: '/api/v1',
    transport: 'cookie',
    // Cookie transport: tokens travel as HttpOnly cookies, never through JS.
    getAccessToken: () => null,
    getRefreshToken: () => null,
    onTokensRefreshed: () => {},
    onAuthFailed: () => {
        // Don't loop — if we're already on /login, let the form do its job.
        // Otherwise redirect (session expired mid-session).
        if (!isOnLoginPage()) {
            window.location.href = '/login';
        }
    },
    skipRefreshPaths: ['/auth/refresh', '/auth/login', '/auth/register'],
});

export default client;
