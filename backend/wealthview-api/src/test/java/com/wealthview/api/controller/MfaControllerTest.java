package com.wealthview.api.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.auth.mfa.MfaService;
import com.wealthview.core.auth.mfa.dto.MfaRecoveryCodesResponse;
import com.wealthview.core.auth.mfa.dto.MfaSetupResponse;
import com.wealthview.core.auth.mfa.dto.MfaStatusResponse;

import static com.wealthview.api.testutil.ControllerTestUtils.USER_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MfaController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class MfaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MfaService mfaService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SessionStateValidator sessionStateValidator;

    @Test
    void setup_authenticated_returns200() throws Exception {
        when(mfaService.setupMfa(USER_ID)).thenReturn(
                new MfaSetupResponse("JBSWY3DPEHPK3PXP", "otpauth://totp/WealthView?secret=JBSWY3DPEHPK3PXP",
                        List.of("code1", "code2", "code3", "code4", "code5")));

        mockMvc.perform(post("/api/v1/auth/mfa/setup")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("JBSWY3DPEHPK3PXP"))
                .andExpect(jsonPath("$.recovery_codes").isArray());
    }

    @Test
    void setup_unauthenticated_returns403() throws Exception {
        // POST without a CSRF token is denied by the CSRF filter (403) before
        // the auth check, which is the correct layered security behavior.
        mockMvc.perform(post("/api/v1/auth/mfa/setup"))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifySetup_validCode_returns204() throws Exception {
        when(mfaService.verifySetup(eq(USER_ID), eq("123456"))).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/mfa/verify-setup")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totp_code": "123456"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void verifySetup_invalidCode_returns401() throws Exception {
        // When verifySetup returns false, the controller throws BadCredentialsException → 401
        when(mfaService.verifySetup(eq(USER_ID), eq("000000"))).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/mfa/verify-setup")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totp_code": "000000"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void verifySetup_missingCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/mfa/verify-setup")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void disable_validCode_returns204() throws Exception {
        when(mfaService.disable(eq(USER_ID), eq("123456"))).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/mfa/disable")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totp_code": "123456"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void disable_invalidCode_returns401() throws Exception {
        // When disable returns false, controller throws BadCredentialsException → 401
        when(mfaService.disable(eq(USER_ID), eq("999999"))).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/mfa/disable")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totp_code": "999999"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void regenerateRecoveryCodes_authenticated_returns200() throws Exception {
        when(mfaService.regenerateRecoveryCodes(USER_ID))
                .thenReturn(new MfaRecoveryCodesResponse(List.of("code-a", "code-b")));

        mockMvc.perform(post("/api/v1/auth/mfa/regenerate-recovery-codes")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recovery_codes").isArray())
                .andExpect(jsonPath("$.recovery_codes[0]").value("code-a"));
    }

    @Test
    void status_authenticated_returns200() throws Exception {
        when(mfaService.status(USER_ID))
                .thenReturn(new MfaStatusResponse(true, OffsetDateTime.now(), 5L));

        mockMvc.perform(get("/api/v1/auth/mfa/status")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.recovery_codes_remaining").value(5));
    }
}
