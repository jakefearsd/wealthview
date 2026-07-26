package com.wealthview.app.it.testutil;

import java.util.List;
import java.util.Map;

import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.wealthview.app.it.AuthHelper;

/**
 * Authenticated HTTP client for integration tests. Replaces the raw
 * {@code restTemplate.exchange(url, method, authHelper.authEntity(body, token), MAP_TYPE)}
 * incantation with intent-revealing verbs.
 *
 * <p>Two families of methods:
 * <ul>
 *   <li>Body-returning ({@link #post}, {@link #get}, {@link #put}, {@link #delete},
 *       {@link #getList}) — for tests that only care about the response payload.</li>
 *   <li>Entity-returning ({@link #postForEntity}, {@link #getForEntity}, ...) — for
 *       tests that assert on status codes or headers.</li>
 * </ul>
 *
 * <p>The plain variants act as the bootstrapped admin ({@link AuthHelper#adminToken()},
 * resolved per call so re-bootstrapping mid-class works). The {@code *As} variants take
 * an explicit access token for multi-user / cross-tenant isolation tests.
 */
public class ApiClient {

    public static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    public static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final TestRestTemplate restTemplate;
    private final AuthHelper authHelper;

    public ApiClient(TestRestTemplate restTemplate, AuthHelper authHelper) {
        this.restTemplate = restTemplate;
        this.authHelper = authHelper;
    }

    // ── Body-returning, as the bootstrapped admin ───────────────────────

    public Map<String, Object> post(String url, Object body) {
        return postForEntity(url, body).getBody();
    }

    public Map<String, Object> get(String url) {
        return getForEntity(url).getBody();
    }

    public Map<String, Object> put(String url, Object body) {
        return putForEntity(url, body).getBody();
    }

    public Map<String, Object> delete(String url) {
        return deleteForEntity(url).getBody();
    }

    public List<Map<String, Object>> getList(String url) {
        return getListForEntity(url).getBody();
    }

    // ── Body-returning, as an explicit token ────────────────────────────

    public Map<String, Object> postAs(String token, String url, Object body) {
        return postForEntityAs(token, url, body).getBody();
    }

    public Map<String, Object> getAs(String token, String url) {
        return getForEntityAs(token, url).getBody();
    }

    public Map<String, Object> putAs(String token, String url, Object body) {
        return putForEntityAs(token, url, body).getBody();
    }

    public Map<String, Object> deleteAs(String token, String url) {
        return deleteForEntityAs(token, url).getBody();
    }

    public List<Map<String, Object>> getListAs(String token, String url) {
        return getListForEntityAs(token, url).getBody();
    }

    // ── Entity-returning, as the bootstrapped admin ─────────────────────

    public ResponseEntity<Map<String, Object>> postForEntity(String url, Object body) {
        return postForEntityAs(authHelper.adminToken(), url, body);
    }

    public ResponseEntity<Map<String, Object>> getForEntity(String url) {
        return getForEntityAs(authHelper.adminToken(), url);
    }

    public ResponseEntity<Map<String, Object>> putForEntity(String url, Object body) {
        return putForEntityAs(authHelper.adminToken(), url, body);
    }

    public ResponseEntity<Map<String, Object>> deleteForEntity(String url) {
        return deleteForEntityAs(authHelper.adminToken(), url);
    }

    public ResponseEntity<List<Map<String, Object>>> getListForEntity(String url) {
        return getListForEntityAs(authHelper.adminToken(), url);
    }

    // ── Entity-returning, as an explicit token ──────────────────────────

    public ResponseEntity<Map<String, Object>> postForEntityAs(String token, String url, Object body) {
        return exchange(token, url, HttpMethod.POST, body);
    }

    public ResponseEntity<Map<String, Object>> getForEntityAs(String token, String url) {
        return exchange(token, url, HttpMethod.GET, null);
    }

    public ResponseEntity<Map<String, Object>> putForEntityAs(String token, String url, Object body) {
        return exchange(token, url, HttpMethod.PUT, body);
    }

    public ResponseEntity<Map<String, Object>> deleteForEntityAs(String token, String url) {
        return exchange(token, url, HttpMethod.DELETE, null);
    }

    public ResponseEntity<List<Map<String, Object>>> getListForEntityAs(String token, String url) {
        return restTemplate.exchange(url, HttpMethod.GET, authEntity(token, null), LIST_MAP_TYPE);
    }

    // ── Anonymous (no auth) ──────────────────────────────────────────────
    //
    // For endpoints/tests that deliberately send no credentials — e.g. pinning
    // a 401/403 rejection, or a genuinely public endpoint like version-check.
    // These are request-shape equivalent to the raw
    // `restTemplate.exchange(url, method, HttpEntity.EMPTY, ...)` incantation
    // they replace: no Authorization header, no auth cookie.

    public Map<String, Object> getAnon(String url) {
        return getAnonForEntity(url).getBody();
    }

    public Map<String, Object> postAnon(String url, Object body) {
        return postAnonForEntity(url, body).getBody();
    }

    public ResponseEntity<Map<String, Object>> getAnonForEntity(String url) {
        return anonExchange(url, HttpMethod.GET, null, MAP_TYPE);
    }

    /**
     * Generic form for call sites that assert on a non-JSON-map body (e.g.
     * {@code String.class} to inspect a raw error payload) rather than parsing
     * into {@link #MAP_TYPE}.
     */
    public <T> ResponseEntity<T> getAnonForEntity(String url, Class<T> responseType) {
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, responseType);
    }

    public ResponseEntity<Map<String, Object>> postAnonForEntity(String url) {
        return anonExchange(url, HttpMethod.POST, null, MAP_TYPE);
    }

    public ResponseEntity<Map<String, Object>> postAnonForEntity(String url, Object body) {
        return anonExchange(url, HttpMethod.POST, body, MAP_TYPE);
    }

    // ── Internals ────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> exchange(String token, String url,
                                                         HttpMethod method, Object body) {
        return restTemplate.exchange(url, method, authEntity(token, body), MAP_TYPE);
    }

    private HttpEntity<?> authEntity(String token, Object body) {
        return body == null ? authHelper.authEntity(token) : authHelper.authEntity(body, token);
    }

    private <T> ResponseEntity<T> anonExchange(String url, HttpMethod method, Object body,
                                                ParameterizedTypeReference<T> type) {
        HttpEntity<?> entity = body == null ? HttpEntity.EMPTY : new HttpEntity<>(body, jsonHeaders());
        return restTemplate.exchange(url, method, entity, type);
    }

    private HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
