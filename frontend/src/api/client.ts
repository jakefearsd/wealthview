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

client.interceptors.response.use(
    (response) => response,
    async (error) => {
        const originalRequest = error.config;
        if (error.response?.status === 401 && !originalRequest._retry) {
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
                window.location.href = '/login';
                return Promise.reject(refreshError);
            } finally {
                isRefreshing = false;
            }
        }
        return Promise.reject(error);
    }
);

export default client;
