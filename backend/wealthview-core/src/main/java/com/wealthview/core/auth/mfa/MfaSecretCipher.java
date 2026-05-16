package com.wealthview.core.auth.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption of TOTP shared secrets at rest.
 *
 * <p>The key is supplied as a Base64-encoded 32-byte value via the
 * {@code MFA_ENCRYPTION_KEY} environment variable. Each encrypt() call
 * generates a fresh 12-byte IV; the IV is prepended to the ciphertext+tag
 * and the whole blob is Base64-encoded for column storage.
 *
 * <p>This is the standard Java GCM pattern. We deliberately avoid rolling
 * any custom KDF / mode — a leak of the column should be deterministically
 * undecryptable without the key, and that's exactly what AES-256-GCM
 * delivers.
 */
@Component
public final class MfaSecretCipher {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec keySpec;
    private final SecureRandom rng = new SecureRandom();

    public MfaSecretCipher(@Value("${app.mfa.encryption-key}") String base64Key) {
        var keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "MFA_ENCRYPTION_KEY must be a Base64-encoded 32-byte value (got "
                            + keyBytes.length + " bytes after decoding)");
        }
        this.keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    public String encrypt(String plaintext) {
        try {
            var iv = new byte[IV_LENGTH];
            rng.nextBytes(iv);
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("MFA secret encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            var combined = Base64.getDecoder().decode(ciphertext);
            var iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            var rest = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, rest, 0, rest.length);
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(rest), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("MFA secret decryption failed", e);
        }
    }
}
