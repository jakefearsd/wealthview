package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.AuthService;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.auth.dto.AuthResult;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.EMAIL;
import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.USER_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private SessionStateValidator sessionStateValidator;

    @Test
    void login_validCredentials_returns200_setsAuthCookies_omitsTokensFromBody() throws Exception {
        var result = new AuthResult("access-jwt", "refresh-jwt",
                UUID.randomUUID(), UUID.randomUUID(), "test@example.com", "admin");
        when(authService.login(any(LoginRequest.class), anyString())).thenReturn(result);
        when(jwtTokenProvider.getAccessTokenExpirationMs()).thenReturn(3_600_000L);
        when(jwtTokenProvider.getRefreshTokenExpirationMs()).thenReturn(86_400_000L);

        var mvcResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "test@example.com", "password": "mytestpass"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn();

        List<String> cookies = mvcResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anyMatch(c -> c.startsWith("access_token=access-jwt")
                && c.contains("HttpOnly") && c.contains("SameSite=Strict") && c.contains("Path=/"));
        assertThat(cookies).anyMatch(c -> c.startsWith("refresh_token=refresh-jwt")
                && c.contains("HttpOnly") && c.contains("SameSite=Strict"));
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new BadCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "test@example.com", "password": "wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void register_validInput_returns201_setsAuthCookies() throws Exception {
        var result = new AuthResult("access", "refresh",
                UUID.randomUUID(), UUID.randomUUID(), "new@example.com", "member");
        when(authService.register(any(RegisterRequest.class))).thenReturn(result);
        when(jwtTokenProvider.getAccessTokenExpirationMs()).thenReturn(3_600_000L);
        when(jwtTokenProvider.getRefreshTokenExpirationMs()).thenReturn(86_400_000L);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "new@example.com", "password": "mytestpass", "invite_code": "ABC123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.role").value("member"))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void register_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refresh_readsRefreshTokenFromCookie_returnsNewCookies() throws Exception {
        var result = new AuthResult("new-access", "new-refresh",
                UUID.randomUUID(), UUID.randomUUID(), "test@example.com", "admin");
        when(authService.refresh("valid-refresh-token")).thenReturn(result);
        when(jwtTokenProvider.getAccessTokenExpirationMs()).thenReturn(3_600_000L);
        when(jwtTokenProvider.getRefreshTokenExpirationMs()).thenReturn(86_400_000L);

        var mvcResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(csrf())
                        .cookie(new Cookie("refresh_token", "valid-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andReturn();

        List<String> cookies = mvcResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anyMatch(c -> c.startsWith("access_token=new-access"));
        assertThat(cookies).anyMatch(c -> c.startsWith("refresh_token=new-refresh"));
    }

    @Test
    void refresh_withoutRefreshCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_clearsAuthCookies() throws Exception {
        var mvcResult = mockMvc.perform(post("/api/v1/auth/logout")
                        .with(csrf())
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent())
                .andReturn();

        List<String> cookies = mvcResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anyMatch(c -> c.startsWith("access_token=") && c.contains("Max-Age=0"));
        assertThat(cookies).anyMatch(c -> c.startsWith("refresh_token=") && c.contains("Max-Age=0"));
    }

    @Test
    void me_authenticated_returnsCurrentUser() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.tenant_id").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void me_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
