import http, { RefinedResponse, ResponseType } from 'k6/http';
import { check } from 'k6';
import type { TenantManifest } from './config';
import { BASE_URL } from './config';

export interface Session {
    xsrf: string;
    ok: boolean;
}

// Captured access-token cookie value for this VU (module state is per-VU and
// persists across iterations). We hash the password only ONCE per VU and then
// re-assert the captured token each iteration. This keeps the workload
// representative (real clients log in once, not every request) and stops bcrypt
// password hashing from dominating the CPU profile of an otherwise read/compute
// workload. Empirically, k6's per-VU cookie jar does NOT reliably carry the
// httpOnly access_token across iterations, so relying on the jar alone leaves
// iterations 2+ unauthenticated — hence the explicit re-assert below.
let authToken = '';

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

// Ensure this VU is authenticated for the current iteration, hashing the
// password at most once per VU. On the first call we log in and capture the
// access_token cookie value; on later calls we just re-assert that cookie into
// the jar (cheap, no bcrypt). If the token can't be read back from the jar,
// authToken stays empty and we simply log in again next iteration — correct,
// just not amortized — so this can never hard-fail authentication.
export function loginOnce(tenant: TenantManifest): void {
    const jar = http.cookieJar();
    if (authToken) {
        jar.set(BASE_URL, 'access_token', authToken);
        return;
    }
    if (login(tenant).ok) {
        const cookies = jar.cookiesForURL(BASE_URL);
        authToken = cookies['access_token'] ? cookies['access_token'][0] : '';
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
