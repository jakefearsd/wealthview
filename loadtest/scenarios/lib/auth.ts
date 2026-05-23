import http, { RefinedResponse, ResponseType } from 'k6/http';
import { check } from 'k6';
import type { TenantManifest } from './config';
import { BASE_URL } from './config';

export interface Session {
    xsrf: string;
    ok: boolean;
}

// Per-VU flag: each VU gets its own module instance and its own cookie jar,
// both of which persist across iterations, so we authenticate once per VU and
// reuse the session. This keeps the workload representative (real clients log
// in once, not every request) and stops bcrypt password hashing from dominating
// the CPU profile of an otherwise read/compute-bound workload.
let authenticated = false;

// Reads the current CSRF token straight from the per-VU cookie jar. The server
// uses double-submit-cookie CSRF and ROTATES the XSRF-TOKEN cookie on every
// response, so the token must be read fresh at POST time — a value captured at
// login is stale by the next request and the POST would 403.
function currentXsrf(): string {
    const cookies = http.cookieJar().cookiesForURL(BASE_URL);
    return cookies['XSRF-TOKEN'] ? cookies['XSRF-TOKEN'][0] : '';
}

// Logs in; k6's per-VU cookie jar retains the auth cookies automatically.
export function login(tenant: TenantManifest): Session {
    const res = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email: tenant.email, password: tenant.password }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
    );
    const ok = res.status === 200;
    check(res, { 'login 200': () => ok });
    return { xsrf: currentXsrf(), ok };
}

// Authenticate at most once per VU. Subsequent calls are no-ops while the VU's
// cookie jar still holds a valid session. Only latches on success, so a failed
// first attempt is retried on the next iteration.
export function loginOnce(tenant: TenantManifest): void {
    if (authenticated) {
        return;
    }
    if (login(tenant).ok) {
        authenticated = true;
    }
}

export function authedGet(path: string, name: string): RefinedResponse<ResponseType | undefined> {
    return http.get(`${BASE_URL}${path}`, { tags: { name } });
}

export function authedPost(path: string, body: unknown, name: string, _session?: Session) {
    return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
        // Read the token fresh from the jar (it rotates per response).
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': currentXsrf() },
        tags: { name },
    });
}
