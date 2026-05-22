import http, { RefinedResponse, ResponseType } from 'k6/http';
import { check } from 'k6';
import type { TenantManifest } from './config';
import { BASE_URL } from './config';

export interface Session {
    xsrf: string;
}

// Logs in; k6's per-VU cookie jar retains the auth cookies automatically.
export function login(tenant: TenantManifest): Session {
    const res = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email: tenant.email, password: tenant.password }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
    );
    check(res, { 'login 200': (r) => r.status === 200 });

    const jar = http.cookieJar();
    const cookies = jar.cookiesForURL(BASE_URL);
    const xsrf = cookies['XSRF-TOKEN'] ? cookies['XSRF-TOKEN'][0] : '';
    return { xsrf };
}

export function authedGet(path: string, name: string): RefinedResponse<ResponseType | undefined> {
    return http.get(`${BASE_URL}${path}`, { tags: { name } });
}

export function authedPost(path: string, body: unknown, name: string, session: Session) {
    return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': session.xsrf },
        tags: { name },
    });
}
