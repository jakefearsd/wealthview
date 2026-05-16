package com.wealthview.api.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.auth.mfa.MfaService;
import com.wealthview.core.auth.mfa.dto.MfaCodeRequest;
import com.wealthview.core.auth.mfa.dto.MfaRecoveryCodesResponse;
import com.wealthview.core.auth.mfa.dto.MfaSetupResponse;
import com.wealthview.core.auth.mfa.dto.MfaStatusResponse;

/**
 * Self-service MFA management. Endpoints all act on the authenticated user.
 * The challenge step (used during login) lives on {@link AuthController} and
 * {@link AuthMobileController}; here we only manage setup, verify, disable,
 * and recovery-code regeneration.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @PostMapping("/setup")
    public ResponseEntity<MfaSetupResponse> setup(@AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(mfaService.setupMfa(principal.userId()));
    }

    @PostMapping("/verify-setup")
    public ResponseEntity<Void> verifySetup(@AuthenticationPrincipal TenantUserPrincipal principal,
                                            @Valid @RequestBody MfaCodeRequest request) {
        if (!mfaService.verifySetup(principal.userId(), request.totpCode())) {
            throw new BadCredentialsException("Invalid TOTP code");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/disable")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal TenantUserPrincipal principal,
                                        @Valid @RequestBody MfaCodeRequest request) {
        if (!mfaService.disable(principal.userId(), request.totpCode())) {
            throw new BadCredentialsException("Invalid TOTP code");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/regenerate-recovery-codes")
    public ResponseEntity<MfaRecoveryCodesResponse> regenerate(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.OK).body(
                mfaService.regenerateRecoveryCodes(principal.userId()));
    }

    @GetMapping("/status")
    public ResponseEntity<MfaStatusResponse> status(@AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(mfaService.status(principal.userId()));
    }
}
