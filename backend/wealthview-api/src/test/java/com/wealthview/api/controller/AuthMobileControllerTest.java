package com.wealthview.api.controller;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.auth.AuthRequestContext;
import com.wealthview.core.auth.AuthService;
import com.wealthview.core.auth.dto.AuthResult;
import com.wealthview.core.auth.dto.LoginOutcome;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.MobileAuthResponse;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.errorEnvelope;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(AuthMobileController.class)
class AuthMobileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    private AuthResult sampleAuthResult() {
        return new AuthResult("access-jwt", "refresh-jwt",
                UUID.randomUUID(), UUID.randomUUID(), "mobile@example.com", "member");
    }

    @Test
    void login_validCredentials_returnsTokensInBody() throws Exception {
        var result = sampleAuthResult();
        when(authService.loginInitiate(any(LoginRequest.class), any()))
                .thenReturn(new LoginOutcome.Tokens(result));

        mockMvc.perform(post("/api/v1/auth/token/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "mobile@example.com", "password": "mypassword1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("access-jwt"))
                .andExpect(jsonPath("$.refresh_token").value("refresh-jwt"))
                .andExpect(jsonPath("$.email").value("mobile@example.com"));
    }

    @Test
    void login_mfaRequired_returnsMfaChallenge() throws Exception {
        // This covers the MfaRequired branch in AuthMobileController.login()
        when(authService.loginInitiate(any(LoginRequest.class), any()))
                .thenReturn(new LoginOutcome.MfaRequired("mfa-challenge-token-xyz"));

        mockMvc.perform(post("/api/v1/auth/token/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "mobile@example.com", "password": "mypassword1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa_required").value(true))
                .andExpect(jsonPath("$.mfa_token").value("mfa-challenge-token-xyz"));
    }

    @Test
    void login_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "mobile@example.com"}
                                """))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST));
    }

    @Test
    void register_validInput_returns201() throws Exception {
        when(authService.register(any(), any(AuthRequestContext.class))).thenReturn(sampleAuthResult());

        mockMvc.perform(post("/api/v1/auth/token/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "new@example.com", "password": "mypassword1", "invite_code": "INVITE1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.access_token").value("access-jwt"))
                .andExpect(jsonPath("$.email").value("mobile@example.com"));
    }

    @Test
    void register_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "new@example.com"}
                                """))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST));
    }

    @Test
    void refresh_validToken_returns200() throws Exception {
        when(authService.refresh(any(), any(AuthRequestContext.class))).thenReturn(sampleAuthResult());

        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refresh_token": "old-refresh-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("access-jwt"));
    }

    @Test
    void refresh_missingToken_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST));
    }

    @Test
    void mfaChallenge_validRequest_returns200() throws Exception {
        when(authService.completeMfaChallenge(any(), any(), any(), any()))
                .thenReturn(sampleAuthResult());

        mockMvc.perform(post("/api/v1/auth/token/mfa/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mfa_token": "mfa-token-xyz", "totp_code": "123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("access-jwt"));
    }

    @Test
    void logout_authenticated_returns204() throws Exception {
        doNothing().when(authService).logout(any(), any());

        mockMvc.perform(post("/api/v1/auth/token/logout")
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void logout_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/logout"))
                .andExpect(status().isUnauthorized());
    }
}
