package com.wealthview.core.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import com.wealthview.core.auth.mfa.MfaService;
import com.wealthview.core.common.Entities;
import com.wealthview.persistence.entity.MfaChallengeEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.MfaChallengeRepository;
import com.wealthview.persistence.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * MFA challenge collaborator for {@link AuthService}: issues the short-lived
 * MFA challenge token + persisted single-use {@code mfa_challenges} row when an
 * MFA-enabled user passes the password check, and verifies the challenge
 * (token validity, JTI binding, expiry, single-use, TOTP/recovery code) when
 * the caller completes it.
 *
 * <p>Extracted from {@code AuthService} as a behavior-preserving decomposition.
 * Every security check moved here unchanged: challenge token validity, the
 * stored-row found/used/expired guard, the JTI-to-user binding check, and the
 * single-use {@code used_at} stamp. {@code AuthService} retains the
 * {@code @Transactional} boundaries; this helper runs inside the orchestrator's
 * transaction.
 */
@Service
class MfaChallengeService {

    private static final Logger log = LoggerFactory.getLogger(MfaChallengeService.class);

    private static final long MFA_CHALLENGE_TTL_MS = 5 * 60 * 1000L;

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final MfaChallengeRepository mfaChallengeRepository;
    private final MfaService mfaService;
    private final MeterRegistry meterRegistry;

    MfaChallengeService(UserRepository userRepository,
                        JwtTokenProvider jwtTokenProvider,
                        MfaChallengeRepository mfaChallengeRepository,
                        MfaService mfaService,
                        MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.mfaChallengeRepository = mfaChallengeRepository;
        this.mfaService = mfaService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Issue an MFA challenge for an authenticated-but-not-yet-token-issued user.
     * Persists a single-use {@code mfa_challenges} row and returns the short-lived
     * challenge JWT the caller must present to {@link #complete}.
     */
    String issueChallenge(UserEntity user, String transport) {
        // Don't issue access/refresh tokens yet. The caller must clear
        // the MFA challenge first by POSTing the totp/recovery code along
        // with the short-lived mfa_token JWT we hand back here.
        var jti = UUID.randomUUID();
        var mfaToken = jwtTokenProvider.generateMfaChallenge(
                user.getId(), transport, jti, MFA_CHALLENGE_TTL_MS);
        mfaChallengeRepository.save(new MfaChallengeEntity(
                user.getTenantId(), user.getId(), jti, transport,
                OffsetDateTime.now().plus(java.time.Duration.ofMillis(MFA_CHALLENGE_TTL_MS))));
        meterRegistry.counter("wealthview.auth.login",
                "result", "mfa_required", "reason", "ok", "transport", transport).increment();
        log.info("MFA challenge issued for user {}", user.getId());
        return mfaToken;
    }

    /**
     * Verify an MFA challenge. Validates the challenge token, the persisted
     * single-use row (found / unused / unexpired / bound to the same user), and
     * the supplied TOTP or recovery code; on success marks the challenge used
     * and returns the verified user for the orchestrator to complete the login.
     * Throws {@link BadCredentialsException} for every rejection reason. The
     * verification logic is preserved verbatim from the original
     * {@code AuthService.completeMfaChallenge}.
     *
     * @return the verified user whose MFA challenge succeeded
     */
    UserEntity complete(String mfaToken, String totpCode, String recoveryCode) {
        if (!jwtTokenProvider.validateMfaChallenge(mfaToken)) {
            log.warn("MFA challenge failed: invalid token");
            meterRegistry.counter("wealthview.mfa.failed", "scope", "challenge_token").increment();
            throw new BadCredentialsException("Invalid MFA challenge");
        }
        var jti = jwtTokenProvider.extractJti(mfaToken);
        var stored = mfaChallengeRepository.findByJti(jti).orElse(null);
        if (stored == null || stored.getUsedAt() != null
                || stored.getExpiresAt().isBefore(OffsetDateTime.now())) {
            log.warn("MFA challenge failed: jti {} not found / used / expired", jti);
            meterRegistry.counter("wealthview.mfa.failed", "scope", "challenge_token").increment();
            throw new BadCredentialsException("Invalid MFA challenge");
        }
        var userId = jwtTokenProvider.extractUserId(mfaToken);
        if (!stored.getUserId().equals(userId)) {
            log.warn("MFA challenge failed: jti {} bound to user {} but token says {}",
                    jti, stored.getUserId(), userId);
            meterRegistry.counter("wealthview.mfa.failed", "scope", "challenge_token").increment();
            throw new BadCredentialsException("Invalid MFA challenge");
        }
        boolean codeOk;
        if (totpCode != null && !totpCode.isBlank()) {
            codeOk = mfaService.verifyTotp(userId, totpCode);
        } else if (recoveryCode != null && !recoveryCode.isBlank()) {
            codeOk = mfaService.consumeRecoveryCode(userId, recoveryCode);
        } else {
            throw new BadCredentialsException("Missing TOTP or recovery code");
        }
        if (!codeOk) {
            throw new BadCredentialsException("Invalid MFA code");
        }
        stored.setUsedAt(OffsetDateTime.now());
        mfaChallengeRepository.save(stored);

        return userRepository.findById(userId).orElseThrow(Entities.notFound("User"));
    }
}
