package com.wealthview.core.auth.mfa;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.auth.mfa.dto.MfaRecoveryCodesResponse;
import com.wealthview.core.auth.mfa.dto.MfaSetupResponse;
import com.wealthview.core.auth.mfa.dto.MfaStatusResponse;
import com.wealthview.core.common.Entities;
import com.wealthview.persistence.entity.MfaRecoveryCodeEntity;
import com.wealthview.persistence.repository.MfaRecoveryCodeRepository;
import com.wealthview.persistence.repository.UserRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_LENGTH = 8;
    /** Crockford-ish alphabet: removes 0/O/1/I/L confusables. */
    private static final char[] RECOVERY_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final String OTP_ISSUER = "WealthView";

    private final UserRepository userRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final MfaSecretCipher cipher;
    private final PasswordEncoder passwordEncoder;
    private final MeterRegistry meterRegistry;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    private final CodeVerifier codeVerifier;
    private final SecureRandom rng = new SecureRandom();

    public MfaService(UserRepository userRepository,
                      MfaRecoveryCodeRepository recoveryCodeRepository,
                      MfaSecretCipher cipher,
                      PasswordEncoder passwordEncoder,
                      MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
        this.meterRegistry = meterRegistry;
        var verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        // Allow ±1 30-second time-step (so up to ±30 seconds of skew). The
        // library counts the current step plus N before/after, so 1 means
        // current ± 1, hardly forgiving but enough for normal phone drift.
        verifier.setAllowedTimePeriodDiscrepancy(1);
        verifier.setTimePeriod(30);
        this.codeVerifier = verifier;
    }

    @Transactional
    public MfaSetupResponse setupMfa(UUID userId) {
        var user = userRepository.findById(userId).orElseThrow(Entities.notFound("User"));

        // Replace any prior unverified secret/recovery codes with a fresh batch.
        var secret = secretGenerator.generate();
        user.setMfaSecretEncrypted(cipher.encrypt(secret));
        user.setMfaSetupAt(null);
        user.setMfaEnabled(false);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        recoveryCodeRepository.deleteAllForUser(userId);
        var plainCodes = generateRecoveryCodes();
        for (var code : plainCodes) {
            recoveryCodeRepository.save(new MfaRecoveryCodeEntity(
                    user.getTenantId(), userId, passwordEncoder.encode(code)));
        }

        var qrUri = new QrData.Builder()
                .label(user.getEmail())
                .secret(secret)
                .issuer(OTP_ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build()
                .getUri();

        meterRegistry.counter("wealthview.mfa.setup").increment();
        log.info("MFA setup initiated for user {}", userId);
        return new MfaSetupResponse(secret, qrUri, plainCodes);
    }

    @Transactional
    public boolean verifySetup(UUID userId, String totpCode) {
        var user = userRepository.findById(userId).orElseThrow(Entities.notFound("User"));
        if (user.getMfaSecretEncrypted() == null) {
            return false;
        }
        var secret = cipher.decrypt(user.getMfaSecretEncrypted());
        if (!codeVerifier.isValidCode(secret, totpCode)) {
            meterRegistry.counter("wealthview.mfa.failed", "scope", "verify_setup").increment();
            return false;
        }
        user.setMfaEnabled(true);
        user.setMfaSetupAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        meterRegistry.counter("wealthview.mfa.verified").increment();
        log.info("MFA enabled for user {}", userId);
        return true;
    }

    @Transactional
    public boolean disable(UUID userId, String totpCode) {
        var user = userRepository.findById(userId).orElseThrow(Entities.notFound("User"));
        if (!user.isMfaEnabled() || user.getMfaSecretEncrypted() == null) {
            return false;
        }
        var secret = cipher.decrypt(user.getMfaSecretEncrypted());
        if (!codeVerifier.isValidCode(secret, totpCode)) {
            meterRegistry.counter("wealthview.mfa.failed", "scope", "disable").increment();
            return false;
        }
        user.setMfaEnabled(false);
        user.setMfaSecretEncrypted(null);
        user.setMfaSetupAt(null);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        recoveryCodeRepository.deleteAllForUser(userId);
        log.info("MFA disabled for user {}", userId);
        return true;
    }

    @Transactional
    public MfaRecoveryCodesResponse regenerateRecoveryCodes(UUID userId) {
        var user = userRepository.findById(userId).orElseThrow(Entities.notFound("User"));
        recoveryCodeRepository.deleteAllForUser(userId);
        var plainCodes = generateRecoveryCodes();
        for (var code : plainCodes) {
            recoveryCodeRepository.save(new MfaRecoveryCodeEntity(
                    user.getTenantId(), userId, passwordEncoder.encode(code)));
        }
        log.info("Recovery codes regenerated for user {}", userId);
        return new MfaRecoveryCodesResponse(plainCodes);
    }

    @Transactional(readOnly = true)
    public MfaStatusResponse status(UUID userId) {
        var user = userRepository.findById(userId).orElseThrow(Entities.notFound("User"));
        long remaining = recoveryCodeRepository.countByUserIdAndUsedAtIsNull(userId);
        return new MfaStatusResponse(user.isMfaEnabled(), user.getMfaSetupAt(), remaining);
    }

    public boolean verifyTotp(UUID userId, String code) {
        var user = userRepository.findById(userId).orElseThrow(Entities.notFound("User"));
        if (user.getMfaSecretEncrypted() == null) {
            return false;
        }
        var secret = cipher.decrypt(user.getMfaSecretEncrypted());
        var ok = codeVerifier.isValidCode(secret, code);
        if (!ok) {
            meterRegistry.counter("wealthview.mfa.failed", "scope", "challenge").increment();
        }
        return ok;
    }

    @Transactional
    public boolean consumeRecoveryCode(UUID userId, String code) {
        var unused = recoveryCodeRepository.findByUserIdAndUsedAtIsNull(userId);
        for (var row : unused) {
            if (passwordEncoder.matches(code, row.getCodeHash())) {
                row.setUsedAt(OffsetDateTime.now());
                row.setUpdatedAt(OffsetDateTime.now());
                recoveryCodeRepository.save(row);
                log.info("Recovery code consumed for user {}", userId);
                return true;
            }
        }
        meterRegistry.counter("wealthview.mfa.failed", "scope", "recovery_code").increment();
        return false;
    }

    public String generateTotpCodeForTesting(String secret) {
        try {
            return codeGenerator.generate(secret, timeProvider.getTime() / 30);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> generateRecoveryCodes() {
        var codes = new ArrayList<String>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            var sb = new StringBuilder(RECOVERY_CODE_LENGTH);
            for (int j = 0; j < RECOVERY_CODE_LENGTH; j++) {
                sb.append(RECOVERY_ALPHABET[rng.nextInt(RECOVERY_ALPHABET.length)]);
            }
            codes.add(sb.toString());
        }
        return codes;
    }
}
