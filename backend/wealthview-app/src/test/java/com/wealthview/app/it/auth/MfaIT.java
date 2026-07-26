package com.wealthview.app.it.auth;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;
import com.wealthview.app.it.testutil.HttpFixtures;
import com.wealthview.core.auth.mfa.MfaService;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of TOTP MFA: setup, verify-setup, login challenge,
 * disable, recovery codes. Uses the real {@link MfaService} (autowired) to
 * generate valid TOTP codes from the user's secret on the fly.
 */
class MfaIT extends AbstractApiIntegrationTest {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Autowired
    private MfaService mfaService;

    @Autowired
    private JdbcTemplate jdbc;

    // ---- Setup flow -------------------------------------------------------

    @Test
    void mfaSetup_returnsSecretAndRecoveryCodes() {
        var login = tokenLogin();
        var resp = mfaSetup(login.accessToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("secret", "qr_code_uri", "recovery_codes");
        @SuppressWarnings("unchecked")
        var codes = (java.util.List<String>) resp.getBody().get("recovery_codes");
        assertThat(codes).hasSize(10);
    }

    @Test
    void mfaSetup_doesNotEnableUntilVerified() {
        var login = tokenLogin();
        mfaSetup(login.accessToken());

        var status = mfaStatus(login.accessToken());
        assertThat(status.getBody().get("enabled")).isEqualTo(false);
    }

    @Test
    void mfaVerifySetup_withCorrectCode_enablesMfa() {
        var login = tokenLogin();
        var access = login.accessToken();
        var setup = mfaSetup(access);
        var secret = (String) setup.getBody().get("secret");

        var validCode = mfaService.generateTotpCodeForTesting(secret);
        var verify = mfaVerifySetup(access, validCode);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var status = mfaStatus(access);
        assertThat(status.getBody().get("enabled")).isEqualTo(true);
    }

    @Test
    void mfaVerifySetup_withWrongCode_doesNotEnable() {
        var login = tokenLogin();
        var access = login.accessToken();
        mfaSetup(access);

        var verify = mfaVerifySetup(access, "000000");
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var status = mfaStatus(access);
        assertThat(status.getBody().get("enabled")).isEqualTo(false);
    }

    @Test
    void mfaSetup_secondCall_replacesPriorUnverifiedSecret() {
        var login = tokenLogin();
        var access = login.accessToken();
        var first = mfaSetup(access);
        var firstSecret = (String) first.getBody().get("secret");

        var second = mfaSetup(access);
        var secondSecret = (String) second.getBody().get("secret");

        assertThat(secondSecret).isNotEqualTo(firstSecret);
    }

    // ---- Login flow -------------------------------------------------------

    @Test
    void loginWithMfaEnabled_returnsMfaRequired_notTokens_bearer() {
        // Called for its effect only — this test asserts the challenge response, not a TOTP code.
        enableMfaOnAdmin();

        var resp = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("mfa_required")).isEqualTo(true);
        assertThat(resp.getBody()).doesNotContainKey("access_token");
    }

    @Test
    void loginWithMfaEnabled_returnsMfaRequired_notTokens_cookie() {
        enableMfaOnAdmin();

        var resp = api.postAnonForEntity("/api/v1/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("mfa_required")).isEqualTo(true);
        assertThat(resp.getBody()).doesNotContainKey("access_token");
    }

    @Test
    void mfaChallenge_withCorrectTotp_returnsTokens() {
        var secret = enableMfaOnAdmin();
        var login = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var mfaToken = (String) login.getBody().get("mfa_token");
        var totp = mfaService.generateTotpCodeForTesting(secret);

        var resp = challengeBearer(mfaToken, totp, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("access_token", "refresh_token");
    }

    @Test
    void mfaChallenge_withWrongTotp_returns401() {
        enableMfaOnAdmin();
        var login = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var mfaToken = (String) login.getBody().get("mfa_token");

        var resp = challengeBearer(mfaToken, "000000", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mfaChallenge_withRecoveryCode_returnsTokensAndConsumesCode() {
        var setupCodes = enableMfaWithCodes();
        var firstCode = setupCodes.recoveryCodes().get(0);
        var login = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var mfaToken = (String) login.getBody().get("mfa_token");

        var resp = challengeBearer(mfaToken, null, firstCode);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("access_token");

        var status = mfaStatus((String) resp.getBody().get("access_token"));
        assertThat(((Number) status.getBody().get("recovery_codes_remaining")).intValue()).isEqualTo(9);
    }

    @Test
    void mfaChallenge_withReusedRecoveryCode_returns401() {
        var setupCodes = enableMfaWithCodes();
        var firstCode = setupCodes.recoveryCodes().get(0);
        // First login + challenge consumes the recovery code.
        var login1 = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        challengeBearer((String) login1.getBody().get("mfa_token"), null, firstCode);

        var login2 = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var resp = challengeBearer((String) login2.getBody().get("mfa_token"), null, firstCode);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mfaChallenge_withReusedMfaToken_returns401() {
        var secret = enableMfaOnAdmin();
        var login = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var mfaToken = (String) login.getBody().get("mfa_token");
        var totp = mfaService.generateTotpCodeForTesting(secret);

        var first = challengeBearer(mfaToken, totp, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        var second = challengeBearer(mfaToken, mfaService.generateTotpCodeForTesting(secret), null);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mfaChallenge_withExpiredMfaToken_returns401() {
        enableMfaOnAdmin();
        var login = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var mfaToken = (String) login.getBody().get("mfa_token");
        // Backdate the row so the DB-side expiry trips before we even check the JWT.
        jdbc.update("UPDATE mfa_challenges SET expires_at = ? WHERE user_id = ?",
                OffsetDateTime.now().minusMinutes(1), authHelper.adminUserId());

        var resp = challengeBearer(mfaToken, "123456", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- Disable flow -----------------------------------------------------

    @Test
    void mfaDisable_requiresCurrentTotp() {
        var secret = enableMfaOnAdmin();
        // Re-login + clear MFA to grab tokens (use recovery code path to avoid double TOTP work).
        var tokens = loginThroughMfaWithSecret(secret);
        var totp = mfaService.generateTotpCodeForTesting(secret);

        var resp = mfaDisable((String) tokens.get("access_token"), totp);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var newTokens = tokenLogin();
        var status = mfaStatus(newTokens.accessToken());
        assertThat(status.getBody().get("enabled")).isEqualTo(false);
    }

    @Test
    void mfaDisable_withWrongTotp_returns401() {
        var secret = enableMfaOnAdmin();
        var tokens = loginThroughMfaWithSecret(secret);

        var resp = mfaDisable((String) tokens.get("access_token"), "000000");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- Recovery codes ---------------------------------------------------

    @Test
    void regenerateRecoveryCodes_returnsNew() {
        var secret = enableMfaOnAdmin();
        var tokens = loginThroughMfaWithSecret(secret);
        var resp = restTemplate.exchange("/api/v1/auth/mfa/regenerate-recovery-codes",
                HttpMethod.POST, new HttpEntity<>(HttpFixtures.bearerHeaders((String) tokens.get("access_token"))),
                MAP_TYPE);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        var codes = (java.util.List<String>) resp.getBody().get("recovery_codes");
        assertThat(codes).hasSize(10);
    }

    // ---- helpers ----------------------------------------------------------

    private String enableMfaOnAdmin() {
        return enableMfaWithCodes().secret();
    }

    private record EnabledMfa(String secret, java.util.List<String> recoveryCodes) {}

    private EnabledMfa enableMfaWithCodes() {
        var login = tokenLogin();
        var access = login.accessToken();
        var setup = mfaSetup(access);
        var secret = (String) setup.getBody().get("secret");
        @SuppressWarnings("unchecked")
        var codes = (java.util.List<String>) setup.getBody().get("recovery_codes");
        var totp = mfaService.generateTotpCodeForTesting(secret);
        var verify = mfaVerifySetup(access, totp);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return new EnabledMfa(secret, codes);
    }

    private Map<String, Object> loginThroughMfaWithSecret(String secret) {
        var login = api.postAnonForEntity("/api/v1/auth/token/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        var mfaToken = (String) login.getBody().get("mfa_token");
        var totp = mfaService.generateTotpCodeForTesting(secret);
        var resp = challengeBearer(mfaToken, totp, null);
        return resp.getBody();
    }

    private AuthHelper.TokenPair tokenLogin() {
        return authHelper.mobileLogin(restTemplate, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> mfaSetup(String access) {
        return restTemplate.exchange("/api/v1/auth/mfa/setup",
                HttpMethod.POST, new HttpEntity<>(HttpFixtures.bearerHeaders(access)), MAP_TYPE);
    }

    private org.springframework.http.ResponseEntity<Void> mfaVerifySetup(String access, String code) {
        return restTemplate.exchange("/api/v1/auth/mfa/verify-setup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("totp_code", code), HttpFixtures.bearerJsonHeaders(access)),
                Void.class);
    }

    private org.springframework.http.ResponseEntity<Void> mfaDisable(String access, String code) {
        return restTemplate.exchange("/api/v1/auth/mfa/disable",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("totp_code", code), HttpFixtures.bearerJsonHeaders(access)),
                Void.class);
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> mfaStatus(String access) {
        return restTemplate.exchange("/api/v1/auth/mfa/status",
                HttpMethod.GET, new HttpEntity<>(HttpFixtures.bearerHeaders(access)), MAP_TYPE);
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> challengeBearer(
            String mfaToken, String totp, String recovery) {
        // Despite the name (matching the mobile/Bearer transport this challenge
        // feeds into), this call itself carries no Authorization header — the
        // mfa_token in the body IS the credential — so it's a plain anonymous
        // POST like the logins above.
        var body = new HashMap<String, Object>();
        body.put("mfa_token", mfaToken);
        if (totp != null) body.put("totp_code", totp);
        if (recovery != null) body.put("recovery_code", recovery);
        return api.postAnonForEntity("/api/v1/auth/token/mfa/challenge", body);
    }
}
