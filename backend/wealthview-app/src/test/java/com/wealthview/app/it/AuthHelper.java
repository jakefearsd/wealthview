package com.wealthview.app.it;

import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.HttpCookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Integration-test helper for cookie + CSRF auth.
 *
 * <p>Login no longer returns a bearer token. The server sets {@code access_token},
 * {@code refresh_token}, and {@code XSRF-TOKEN} cookies on the response.
 * This helper captures those cookies into a {@link Session} struct that callers
 * pass to {@link #authHeaders} / {@link #authEntity}, which translate them into
 * {@code Cookie:} + {@code X-XSRF-TOKEN:} request headers.
 *
 * <p>The legacy {@link #adminToken()} accessor returns the access-token cookie
 * value as a plain string so existing tests that pass a "token" through to
 * {@code authHeaders(token)} continue to work — but they now produce cookie-auth
 * requests under the hood.
 */
@Component
public class AuthHelper {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final PasswordEncoder passwordEncoder;

    private Session cachedAdminSession;
    private UUID cachedTenantId;
    private UUID cachedAdminUserId;

    private Session cachedTenant2Session;
    private UUID cachedTenant2Id;

    public AuthHelper(TenantRepository tenantRepository,
                      UserRepository userRepository,
                      InviteCodeRepository inviteCodeRepository,
                      PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void bootstrap(TestRestTemplate restTemplate) {
        bootstrapData();
        cachedAdminSession = login(restTemplate, ADMIN_EMAIL, ADMIN_PASSWORD);
        cachedAdminSession = primeCsrf(restTemplate, cachedAdminSession);
    }

    @Transactional
    public void bootstrapData() {
        var tenant = tenantRepository.save(new TenantEntity("IT Test Tenant"));
        cachedTenantId = tenant.getId();

        var admin = new UserEntity(tenant, ADMIN_EMAIL,
                passwordEncoder.encode(ADMIN_PASSWORD), "admin");
        admin = userRepository.save(admin);
        cachedAdminUserId = admin.getId();
    }

    public void bootstrapSecondTenant(TestRestTemplate restTemplate) {
        bootstrapSecondTenantData();
        cachedTenant2Session = login(restTemplate, "it-admin2@wealthview.test", ADMIN_PASSWORD);
        cachedTenant2Session = primeCsrf(restTemplate, cachedTenant2Session);
    }

    @Transactional
    public void bootstrapSecondTenantData() {
        var tenant2 = tenantRepository.save(new TenantEntity("IT Test Tenant 2"));
        cachedTenant2Id = tenant2.getId();

        var admin2 = new UserEntity(tenant2, "it-admin2@wealthview.test",
                passwordEncoder.encode(ADMIN_PASSWORD), "admin");
        userRepository.save(admin2);
    }

    @Transactional
    public String createInviteCode() {
        var tenant = tenantRepository.findById(cachedTenantId).orElseThrow();
        var admin = userRepository.findById(cachedAdminUserId).orElseThrow();
        var code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var invite = new InviteCodeEntity(tenant, code, admin,
                OffsetDateTime.now().plusDays(7));
        inviteCodeRepository.save(invite);
        return code;
    }

    @Transactional
    public String createExpiredInviteCode() {
        var tenant = tenantRepository.findById(cachedTenantId).orElseThrow();
        var admin = userRepository.findById(cachedAdminUserId).orElseThrow();
        var code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var invite = new InviteCodeEntity(tenant, code, admin,
                OffsetDateTime.now().minusDays(1));
        inviteCodeRepository.save(invite);
        return code;
    }

    @Transactional
    public UUID createUserDirectly(String email, String password, String role) {
        var tenant = tenantRepository.findById(cachedTenantId).orElseThrow();
        var user = new UserEntity(tenant, email, passwordEncoder.encode(password), role);
        user = userRepository.save(user);
        return user.getId();
    }

    @Transactional
    public UUID createSuperAdminDirectly(String email, String password) {
        var tenant = tenantRepository.findById(cachedTenantId).orElseThrow();
        var user = new UserEntity(tenant, email, passwordEncoder.encode(password), "admin");
        user.setSuperAdmin(true);
        user = userRepository.save(user);
        return user.getId();
    }

    public String loginAs(TestRestTemplate restTemplate, String email, String password) {
        return login(restTemplate, email, password).accessToken();
    }

    public Session loginAsSession(TestRestTemplate restTemplate, String email, String password) {
        return login(restTemplate, email, password);
    }

    public String registerAndGetToken(TestRestTemplate restTemplate,
                                      String email, String password, String inviteCode) {
        var body = Map.of(
                "email", email,
                "password", password,
                "invite_code", inviteCode
        );
        var response = restTemplate.exchange("/api/v1/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        return parseSession(response.getHeaders()).accessToken();
    }

    public String adminToken() {
        return cachedAdminSession.accessToken();
    }

    public Session adminSession() {
        return cachedAdminSession;
    }

    public UUID tenantId() {
        return cachedTenantId;
    }

    public UUID adminUserId() {
        return cachedAdminUserId;
    }

    public String tenant2Token() {
        return cachedTenant2Session.accessToken();
    }

    public Session tenant2Session() {
        return cachedTenant2Session;
    }

    public UUID tenant2Id() {
        return cachedTenant2Id;
    }

    /**
     * Build request headers for an authenticated, CSRF-protected call.
     *
     * <p>The {@code token} parameter is the access-token cookie value (kept as a
     * raw string for backwards compatibility with existing call sites). The CSRF
     * token comes from whichever cached session matches this access token.
     */
    public HttpHeaders authHeaders(String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applyAuthCookies(headers, sessionForToken(token));
        return headers;
    }

    /**
     * Apply cookie + CSRF auth onto an existing {@link HttpHeaders} (e.g. one
     * preconfigured for multipart). Does not overwrite Content-Type.
     */
    public void applyAuth(HttpHeaders headers, String token) {
        applyAuthCookies(headers, sessionForToken(token));
    }

    public <T> HttpEntity<T> authEntity(T body, String token) {
        return new HttpEntity<>(body, authHeaders(token));
    }

    public HttpEntity<Void> authEntity(String token) {
        return new HttpEntity<>(authHeaders(token));
    }

    private Session sessionForToken(String token) {
        if (cachedAdminSession != null && token.equals(cachedAdminSession.accessToken())) {
            return cachedAdminSession;
        }
        if (cachedTenant2Session != null && token.equals(cachedTenant2Session.accessToken())) {
            return cachedTenant2Session;
        }
        // Fall back: token is from an ad-hoc login (e.g. via registerAndGetToken
        // or loginAs). We have no CSRF token — use the admin session's CSRF.
        // Safer approach below would track all sessions; this matches existing
        // test usage where tokens not in the cache only need the access cookie.
        return new Session(token, null, cachedAdminSession != null ? cachedAdminSession.csrfToken() : null);
    }

    private void applyAuthCookies(HttpHeaders headers, Session session) {
        var cookieValue = "access_token=" + session.accessToken();
        if (session.csrfToken() != null) {
            cookieValue += "; XSRF-TOKEN=" + session.csrfToken();
            headers.add("X-XSRF-TOKEN", session.csrfToken());
        }
        headers.add(HttpHeaders.COOKIE, cookieValue);
    }

    /**
     * After login, Spring 6 only writes the XSRF-TOKEN cookie when something on
     * the request actually touches the deferred CSRF token. Login is on the
     * CSRF ignore list so the token isn't generated there; do a follow-up GET
     * through the security chain to force cookie issuance.
     */
    private Session primeCsrf(TestRestTemplate restTemplate, Session session) {
        if (session.csrfToken() != null) {
            return session;
        }
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "access_token=" + session.accessToken());
        var resp = restTemplate.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        var primed = parseSessionMerge(resp.getHeaders(), session);
        return primed;
    }

    private Session parseSessionMerge(HttpHeaders headers, Session existing) {
        var setCookies = headers.get(HttpHeaders.SET_COOKIE);
        String csrf = existing.csrfToken();
        if (setCookies != null) {
            for (var sc : setCookies) {
                for (HttpCookie c : HttpCookie.parse(sc)) {
                    if ("XSRF-TOKEN".equals(c.getName())) {
                        csrf = c.getValue();
                    }
                }
            }
        }
        return new Session(existing.accessToken(), existing.refreshToken(), csrf);
    }

    private Session login(TestRestTemplate restTemplate, String email, String password) {
        var body = Map.of("email", email, "password", password);
        var response = restTemplate.exchange("/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        return parseSession(response.getHeaders());
    }

    private Session parseSession(HttpHeaders headers) {
        var setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            throw new IllegalStateException("Auth response had no Set-Cookie headers");
        }
        String access = null;
        String refresh = null;
        String csrf = null;
        for (var sc : setCookies) {
            for (HttpCookie c : HttpCookie.parse(sc)) {
                switch (c.getName()) {
                    case "access_token" -> access = c.getValue();
                    case "refresh_token" -> refresh = c.getValue();
                    case "XSRF-TOKEN" -> csrf = c.getValue();
                    default -> { /* ignore */ }
                }
            }
        }
        if (access == null) {
            throw new IllegalStateException("Auth response missing access_token cookie");
        }
        return new Session(access, refresh, csrf);
    }

    private HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Captured cookies and CSRF token for an authenticated session. */
    public record Session(String accessToken, String refreshToken, String csrfToken) {
        public List<String> cookieHeaderValues() {
            return List.of(
                    "access_token=" + accessToken,
                    "refresh_token=" + (refreshToken == null ? "" : refreshToken)
            );
        }
    }
}
