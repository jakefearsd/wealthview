package com.wealthview.core.auth.mfa;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.wealthview.core.testutil.TestEntityHelper;
import com.wealthview.persistence.entity.MfaRecoveryCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.MfaRecoveryCodeRepository;
import com.wealthview.persistence.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    private static final String VALID_KEY = "REVWRUxPUE1FTlQtT05MWS1NRkEtS0VZLTMyLUJZVEU=";

    @Mock
    private UserRepository userRepository;

    @Mock
    private MfaRecoveryCodeRepository recoveryCodeRepository;

    private MfaSecretCipher cipher;
    private PasswordEncoder passwordEncoder;
    private MfaService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        cipher = new MfaSecretCipher(VALID_KEY);
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new MfaService(userRepository, recoveryCodeRepository, cipher,
                passwordEncoder, new SimpleMeterRegistry());

        var tenant = new TenantEntity("T");
        TestEntityHelper.setId(tenant, UUID.randomUUID());
        user = new UserEntity(tenant, "x@y.z", "hash", "member");
        TestEntityHelper.setId(user, UUID.randomUUID());
    }

    @Test
    void setupMfa_returnsTenRecoveryCodes() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = service.setupMfa(user.getId());

        assertThat(resp.recoveryCodes()).hasSize(10);
        assertThat(resp.secret()).isNotBlank();
        assertThat(resp.qrCodeUri()).startsWith("otpauth://");
    }

    @Test
    void setupMfa_storesEncryptedSecret_doesNotEnableYet() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setupMfa(user.getId());

        assertThat(user.getMfaSecretEncrypted()).isNotBlank();
        assertThat(user.isMfaEnabled()).isFalse();
        // Encrypted blob is not the same as the plaintext secret.
        assertThat(user.getMfaSecretEncrypted()).doesNotContain("JBSW"); // arbitrary marker
    }

    @Test
    void setupMfa_replacesPriorRecoveryCodes() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setupMfa(user.getId());

        verify(recoveryCodeRepository).deleteAllForUser(user.getId());
    }

    @Test
    void verifySetup_withWrongCode_returnsFalse_doesNotEnable() {
        // Pre-seed an encrypted secret on the user so verifySetup has something to check.
        user.setMfaSecretEncrypted(cipher.encrypt("JBSWY3DPEHPK3PXP"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var ok = service.verifySetup(user.getId(), "000000");

        assertThat(ok).isFalse();
        assertThat(user.isMfaEnabled()).isFalse();
    }

    @Test
    void verifySetup_withNoSecret_returnsFalse() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(service.verifySetup(user.getId(), "000000")).isFalse();
    }

    @Test
    void consumeRecoveryCode_correctCode_marksUsedAndReturnsTrue() {
        var plain = "ABCDEFGH";
        var row = new MfaRecoveryCodeEntity(UUID.randomUUID(), user.getId(),
                passwordEncoder.encode(plain));
        TestEntityHelper.setId(row, UUID.randomUUID());
        when(recoveryCodeRepository.findByUserIdAndUsedAtIsNull(user.getId()))
                .thenReturn(List.of(row));

        var ok = service.consumeRecoveryCode(user.getId(), plain);

        assertThat(ok).isTrue();
        assertThat(row.getUsedAt()).isNotNull();
    }

    @Test
    void consumeRecoveryCode_wrongCode_returnsFalse() {
        when(recoveryCodeRepository.findByUserIdAndUsedAtIsNull(user.getId()))
                .thenReturn(List.of());

        assertThat(service.consumeRecoveryCode(user.getId(), "BADCODE7")).isFalse();
    }

    @Test
    void disable_clearsMfaState() {
        user.setMfaEnabled(true);
        user.setMfaSecretEncrypted(cipher.encrypt("JBSWY3DPEHPK3PXP"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Generate a valid TOTP for our embedded secret
        var validCode = service.generateTotpCodeForTesting("JBSWY3DPEHPK3PXP");

        var ok = service.disable(user.getId(), validCode);

        assertThat(ok).isTrue();
        assertThat(user.isMfaEnabled()).isFalse();
        assertThat(user.getMfaSecretEncrypted()).isNull();
        verify(recoveryCodeRepository).deleteAllForUser(user.getId());
    }

    @Test
    void verifySetup_withCorrectCode_enablesMfaAndSetsSetupTimestamp() {
        // Pins that verifySetup actually flips mfaEnabled to true and records
        // a setup timestamp; a mutant that drops setMfaEnabled or returns false
        // would be caught here.
        user.setMfaSecretEncrypted(cipher.encrypt("JBSWY3DPEHPK3PXP"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        var validCode = service.generateTotpCodeForTesting("JBSWY3DPEHPK3PXP");

        var ok = service.verifySetup(user.getId(), validCode);

        assertThat(ok).isTrue();
        assertThat(user.isMfaEnabled()).isTrue();
        assertThat(user.getMfaSetupAt()).isNotNull();
    }

    @Test
    void disable_whenMfaNotEnabled_returnsFalseWithoutTouchingState() {
        // disable must short-circuit when MFA is not enabled — it must not
        // attempt to decrypt a secret or clear recovery codes.
        user.setMfaEnabled(false);
        user.setMfaSecretEncrypted(cipher.encrypt("JBSWY3DPEHPK3PXP"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var ok = service.disable(user.getId(), "000000");

        assertThat(ok).isFalse();
        assertThat(user.getMfaSecretEncrypted()).isNotNull();
        verify(recoveryCodeRepository, org.mockito.Mockito.never()).deleteAllForUser(any());
    }

    @Test
    void disable_withWrongCode_returnsFalseAndDoesNotClearSecret() {
        user.setMfaEnabled(true);
        user.setMfaSecretEncrypted(cipher.encrypt("JBSWY3DPEHPK3PXP"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var ok = service.disable(user.getId(), "000000");

        assertThat(ok).isFalse();
        assertThat(user.isMfaEnabled()).isTrue();
        assertThat(user.getMfaSecretEncrypted()).isNotNull();
    }

    @Test
    void verifyTotp_withNoSecret_returnsFalse() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(service.verifyTotp(user.getId(), "123456")).isFalse();
    }

    @Test
    void verifyTotp_withWrongCode_returnsFalse() {
        // A wrong TOTP must be rejected — a negated-conditional mutant making
        // verification always-true would be caught here.
        user.setMfaSecretEncrypted(cipher.encrypt("JBSWY3DPEHPK3PXP"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(service.verifyTotp(user.getId(), "000000")).isFalse();
    }

    @Test
    void verifyTotp_withCorrectCode_returnsTrue() {
        user.setMfaSecretEncrypted(cipher.encrypt("JBSWY3DPEHPK3PXP"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        var validCode = service.generateTotpCodeForTesting("JBSWY3DPEHPK3PXP");

        assertThat(service.verifyTotp(user.getId(), validCode)).isTrue();
    }

    @Test
    void status_reflectsEnabledFlagAndRemainingRecoveryCodeCount() {
        user.setMfaEnabled(true);
        var setupAt = OffsetDateTime.now().minusDays(2);
        user.setMfaSetupAt(setupAt);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId())).thenReturn(7L);

        var status = service.status(user.getId());

        assertThat(status.enabled()).isTrue();
        assertThat(status.setupAt()).isEqualTo(setupAt);
        assertThat(status.recoveryCodesRemaining()).isEqualTo(7L);
    }

    @Test
    void regenerateRecoveryCodes_deletesOldAndReturnsTenFreshCodes() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var resp = service.regenerateRecoveryCodes(user.getId());

        assertThat(resp.recoveryCodes()).hasSize(10);
        verify(recoveryCodeRepository).deleteAllForUser(user.getId());
        verify(recoveryCodeRepository, org.mockito.Mockito.times(10))
                .save(any(MfaRecoveryCodeEntity.class));
    }
}
