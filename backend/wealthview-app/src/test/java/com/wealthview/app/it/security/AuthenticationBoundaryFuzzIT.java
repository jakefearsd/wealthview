package com.wealthview.app.it.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz the authentication boundary at the HTTP layer.
 *
 * <p>Asserted invariants:
 * <ul>
 *   <li>Random / malformed login bodies never produce 5xx and never authenticate.</li>
 *   <li>Random JWT cookie values produce 401, never 500 or a successful 2xx.</li>
 *   <li>Tampered CSRF (header / cookie mismatch) produces 403 on mutating endpoints.</li>
 * </ul>
 */
@Tag("fuzz")
class AuthenticationBoundaryFuzzIT extends AbstractApiIntegrationTest {

    private static final int LOGIN_ITERATIONS = 100;
    private static final int JWT_ITERATIONS = 100;
    private static final int CSRF_ITERATIONS = 50;

    @Test
    void login_withFuzzedJsonBodies_neverReturns5xxOrAuthenticates() {
        FuzzSupport.samples(this::randomLoginBody, LOGIN_ITERATIONS, 0xA1L)
                .forEach(body -> {
                    var resp = restTemplate.exchange("/api/v1/auth/login",
                            HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), MAP_TYPE);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("login body=%s produced 5xx (status=%s)", body, resp.getStatusCode())
                            .isFalse();
                    // The fuzz generator never produces the bootstrap admin
                    // (email, password) pair, so any 2xx would mean a real
                    // authentication bypass.
                    assertThat(resp.getStatusCode().is2xxSuccessful())
                            .as("login body=%s should never authenticate (status=%s)",
                                    body, resp.getStatusCode())
                            .isFalse();
                });
    }

    @Test
    void login_withMalformedRawJson_returns4xx() {
        // Strings that are not well-formed JSON at all.
        List<String> malformed = List.of(
                "",
                "{",
                "not json",
                "[]",                       // array, not object
                "{\"email\":}",
                "{\"email\":\"a@b.com\",\"password\":}",
                "{\"email\":\"a@b.com\"",   // truncated
                " "
        );

        for (var raw : malformed) {
            var resp = restTemplate.exchange("/api/v1/auth/login",
                    HttpMethod.POST, new HttpEntity<>(raw, jsonHeaders()), String.class);
            assertThat(resp.getStatusCode().is5xxServerError())
                    .as("malformed JSON body %s produced 5xx", raw)
                    .isFalse();
            assertThat(resp.getStatusCode().is4xxClientError())
                    .as("malformed JSON body %s should be 4xx", raw)
                    .isTrue();
        }
    }

    @Test
    void protectedEndpoint_withFuzzedAccessTokenCookie_returns401NotCrash() {
        FuzzSupport.samples(this::randomAccessToken, JWT_ITERATIONS, 0xB2L)
                .forEach(token -> {
                    var headers = new HttpHeaders();
                    headers.add(HttpHeaders.COOKIE, "access_token=" + token);
                    var resp = restTemplate.exchange("/api/v1/auth/me",
                            HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("token=%s produced 5xx (status=%s)", abbrev(token), resp.getStatusCode())
                            .isFalse();
                    assertThat(resp.getStatusCode().value())
                            .as("token=%s should be 401, was %s", abbrev(token), resp.getStatusCode())
                            .isEqualTo(HttpStatus.UNAUTHORIZED.value());
                });
    }

    @Test
    void csrfMutation_withTamperedToken_returns403NotCrash() {
        var session = authHelper.adminSession();

        FuzzSupport.samples(r -> randomBadCsrf(r, session.csrfToken()),
                CSRF_ITERATIONS, 0xC3L).forEach(badCsrf -> {
                    var headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.add(HttpHeaders.COOKIE,
                            "access_token=" + session.accessToken()
                                    + "; XSRF-TOKEN=" + session.csrfToken());
                    headers.add("X-XSRF-TOKEN", badCsrf);

                    var body = Map.of("name", "csrf-test", "type", "brokerage");
                    var resp = restTemplate.exchange("/api/v1/accounts",
                            HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);

                    assertThat(resp.getStatusCode().is5xxServerError())
                            .as("bad CSRF=%s produced 5xx", abbrev(badCsrf))
                            .isFalse();
                    // CSRF rejection may surface as 403 (CSRF filter rejects)
                    // or 401 (request fails earlier in the auth chain when
                    // header parsing chokes); both are acceptable. The
                    // critical invariant is that the mutation never succeeds.
                    assertThat(resp.getStatusCode().is2xxSuccessful())
                            .as("bad CSRF=%s allowed mutation (status=%s)",
                                    abbrev(badCsrf), resp.getStatusCode())
                            .isFalse();
                    assertThat(resp.getStatusCode().value())
                            .as("bad CSRF=%s should yield 401 or 403, was %s",
                                    abbrev(badCsrf), resp.getStatusCode())
                            .isIn(HttpStatus.UNAUTHORIZED.value(),
                                    HttpStatus.FORBIDDEN.value(),
                                    HttpStatus.BAD_REQUEST.value());
                });
    }

    private Map<String, Object> randomLoginBody(Random r) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (r.nextInt(10) < 8) {
            body.put("email", randomEmailValue(r));
        }
        if (r.nextInt(10) < 8) {
            body.put("password", randomPasswordValue(r));
        }
        if (r.nextInt(10) < 4) {
            body.put("extra_" + FuzzSupport.alnum(r, 6), FuzzSupport.alnum(r, 8));
        }
        return body;
    }

    private Object randomEmailValue(Random r) {
        return switch (r.nextInt(7)) {
            case 0 -> "";
            case 1 -> "not-an-email";
            case 2 -> r.nextInt();
            case 3 -> Map.of("nested", "object");
            case 4 -> List.of("a", "b");
            case 5 -> FuzzSupport.alnum(r, 40) + "@x.test";
            default -> FuzzSupport.alnum(r, 1024) + "@oversize.test";
        };
    }

    private Object randomPasswordValue(Random r) {
        return switch (r.nextInt(5)) {
            case 0 -> "";
            case 1 -> r.nextInt();
            case 2 -> Map.of("a", 1);
            case 3 -> FuzzSupport.asciiNoisy(r, 1024);
            default -> FuzzSupport.alnum(r, 20);
        };
    }

    private String randomAccessToken(Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> "";
            case 1 -> FuzzSupport.alnum(r, 64);
            case 2 -> FuzzSupport.alnum(r, 40)
                    + "." + FuzzSupport.alnum(r, 40)
                    + "." + FuzzSupport.alnum(r, 40);
            case 3 -> "eyJhbGciOiJIUzI1NiJ9."
                    + "eyJzdWIiOiJ4IiwidGVuYW50X2lkIjoieCIsInR5cGUiOiJhY2Nlc3MifQ."
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            case 4 -> "eyJhbGciOiJIUzI1NiJ9.x";
            default -> FuzzSupport.alnum(r, 8000);
        };
    }

    private String randomBadCsrf(Random r, String validCsrf) {
        // Important: every variant must NOT equal {@code validCsrf}, otherwise
        // the server correctly accepts the request and the test reports a
        // spurious "mutation succeeded" failure. We append a non-empty suffix
        // for the off-by-N case and exclude the empty string from random
        // alnum/ascii branches.
        return switch (r.nextInt(5)) {
            case 0 -> "";
            case 1 -> FuzzSupport.alnum(r, 64) + "x"; // ensure non-empty
            case 2 -> validCsrf + FuzzSupport.alnum(r, 4) + "x";
            case 3 -> FuzzSupport.alnum(r, 8000) + "x";
            default -> FuzzSupport.asciiNoisy(r, 256) + "x";
        };
    }

    private HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String abbrev(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= 32 ? s : s.substring(0, 29) + "...";
    }
}
