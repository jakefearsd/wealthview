package com.wealthview.core.auth.mfa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCipherTest {

    private static final String VALID_KEY = "REVWRUxPUE1FTlQtT05MWS1NRkEtS0VZLTMyLUJZVEU=";
    private MfaSecretCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new MfaSecretCipher(VALID_KEY);
    }

    @Test
    void encryptThenDecrypt_roundTripsPlaintext() {
        var secret = "JBSWY3DPEHPK3PXP";

        var encrypted = cipher.encrypt(secret);
        var decrypted = cipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(secret);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachCall() {
        // Random IV per call means even the same plaintext yields different
        // ciphertexts; this prevents a passive attacker from inferring whether
        // two users share a secret.
        var secret = "JBSWY3DPEHPK3PXP";

        var c1 = cipher.encrypt(secret);
        var c2 = cipher.encrypt(secret);

        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    void constructor_rejectsKeyWithWrongLength() {
        assertThatThrownBy(() -> new MfaSecretCipher("dGVzdA==")) // 4 bytes
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32-byte");
    }

    @Test
    void decrypt_withDifferentKey_failsLoudly() {
        var encrypted = cipher.encrypt("secret");
        var otherCipher = new MfaSecretCipher("QU5PVEhFUi0zMi1CWVRFLUtFWS1GT1ItTUZBLVRFU1Q=");

        assertThatThrownBy(() -> otherCipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }
}
