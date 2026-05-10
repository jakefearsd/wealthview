import axios from 'axios';

/**
 * Axios client for authenticated API calls.
 *
 * <p>Auth tokens live in HttpOnly cookies set by the backend on login/refresh —
 * never in localStorage, never readable by JavaScript. {@code withCredentials}
 * tells the browser to send those cookies with every request to {@code /api/v1}.
 *
 * <p>CSRF protection works automatically: axios reads the {@code XSRF-TOKEN}
 * cookie (which is NOT HttpOnly) and echoes it as the {@code X-XSRF-TOKEN}
 * header on every request. The backend's CSRF filter rejects mutations whose
 * header value doesn't match the cookie — the standard double-submit pattern.
 */
const client = axios.create({
    baseURL: '/api/v1',
    headers: { 'Content-Type': 'application/json' },
    withCredentials: true,
});

let isRefreshing = false;
let failedQueue: Array<{ resolve: () => void; reject: (err: unknown) => void }> = [];

function processQueue(error: unknown) {
    failedQueue.forEach((prom) => {
        if (error) {
            prom.reject(error);
        } else {
            prom.resolve();
        }
    });
    failedQueue = [];
}

// URLs whose 401 should propagate to the caller as-is, without attempting to
// refresh or redirect. These are auth-shaped probes — for /auth/me a 401
// just means "not logged in," and for /auth/refresh and /auth/login a 401
// means the credentials are bad (no point retrying via refresh).
const AUTH_PROBE_PATHS = ['/auth/me', '/auth/refresh', '/auth/login', '/auth/register'];

function isAuthProbe(url: string | undefined): boolean {
    if (!url) return false;
    return AUTH_PROBE_PATHS.some((p) => url.endsWith(p));
}

function isOnLoginPage(): boolean {
    if (typeof window === 'undefined') return false;
    return window.location.pathname === '/login' || window.location.pathname === '/register';
}

client.interceptors.response.use(
    (response) => response,
    async (error) => {
        const originalRequest = error.config;
        if (
            error.response?.status === 401 &&
            !originalRequest._retry &&
            !isAuthProbe(originalRequest?.url)
        ) {
            if (isRefreshing) {
                return new Promise<void>((resolve, reject) => {
                    failedQueue.push({ resolve, reject });
                }).then(() => client(originalRequest));
            }

            originalRequest._retry = true;
            isRefreshing = true;

            try {
                // Refresh token comes from the HttpOnly refresh_token cookie —
                // we don't pass anything in the body. The response sets new
                // access_token and refresh_token cookies.
                await axios.post('/api/v1/auth/refresh', null, { withCredentials: true });
                processQueue(null);
                return client(originalRequest);
            } catch (refreshError) {
                processQueue(refreshError);
                // Don't loop — if we're already on /login, let the form do its
                // job. Otherwise redirect (session expired in mid-session).
                if (!isOnLoginPage()) {
                    window.location.href = '/login';
                }
                return Promise.reject(refreshError);
            } finally {
                isRefreshing = false;
            }
        }
        return Promise.reject(error);
    }
);

export default client;
