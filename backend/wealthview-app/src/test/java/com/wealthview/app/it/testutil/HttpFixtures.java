package com.wealthview.app.it.testutil;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Static header builders for IT classes that construct raw {@code HttpHeaders}
 * for {@code restTemplate.exchange} calls outside the {@link ApiClient} facade.
 *
 * <p>Mainly serves the Bearer-transport / auth-mechanic test files where the raw
 * {@code exchange} call itself is the thing under test (transport headers, CSRF
 * interplay, tampered tokens) so {@code ApiClient} doesn't model the call — but
 * the {@code new HttpHeaders(); headers.setBearerAuth(...); return headers;}
 * boilerplate around it was duplicated file-by-file.
 */
public final class HttpFixtures {

    private HttpFixtures() {
    }

    /** {@code Content-Type: application/json}, no auth. */
    public static HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** {@code Authorization: Bearer <token>}, no explicit Content-Type. */
    public static HttpHeaders bearerHeaders(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** {@code Authorization: Bearer <token>} plus {@code Content-Type: application/json}. */
    public static HttpHeaders bearerJsonHeaders(String token) {
        var headers = bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
