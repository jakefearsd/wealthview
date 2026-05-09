package com.wealthview.api.security;

import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private SessionStateValidator sessionStateValidator;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        response = new MockHttpServletResponse();
        chain = org.mockito.Mockito.mock(FilterChain.class);
        SecurityContextHolder.clearContext();
        MDC.clear();
        lenient().when(sessionStateValidator.isSessionValid(any(UUID.class), anyInt()))
                .thenReturn(true);
        lenient().when(jwtTokenProvider.extractGeneration(anyString())).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void doFilterInternal_validAccessTokenCookie_populatesSecurityContext() throws ServletException, IOException {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        request.setCookies(new Cookie("access_token", "valid.jwt.token"));
        when(jwtTokenProvider.validateAccessToken("valid.jwt.token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("valid.jwt.token")).thenReturn(userId);
        when(jwtTokenProvider.extractTenantId("valid.jwt.token")).thenReturn(tenantId);
        when(jwtTokenProvider.extractRole("valid.jwt.token")).thenReturn("member");
        when(jwtTokenProvider.extractEmail("valid.jwt.token")).thenReturn("user@test.com");

        filter.doFilterInternal(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(TenantUserPrincipal.class);
        var principal = (TenantUserPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.tenantId()).isEqualTo(tenantId);
        assertThat(principal.email()).isEqualTo("user@test.com");
        assertThat(principal.role()).isEqualTo("member");
        assertThat(principal.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_MEMBER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_adminRole_setsRoleAdminAuthority() throws ServletException, IOException {
        request.setCookies(new Cookie("access_token", "admin.token"));
        when(jwtTokenProvider.validateAccessToken("admin.token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("admin.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractTenantId("admin.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractRole("admin.token")).thenReturn("admin");
        when(jwtTokenProvider.extractEmail("admin.token")).thenReturn("admin@test.com");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void doFilterInternal_invalidToken_leavesSecurityContextEmpty() throws ServletException, IOException {
        request.setCookies(new Cookie("access_token", "bad.token"));
        when(jwtTokenProvider.validateAccessToken("bad.token")).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verify(jwtTokenProvider, never()).extractUserId(anyString());
    }

    @Test
    void doFilterInternal_noCookies_skipsAuthentication() throws ServletException, IOException {
        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void doFilterInternal_validBearerHeader_populatesSecurityContextAndMarksRequest()
            throws ServletException, IOException {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        request.addHeader("Authorization", "Bearer mobile.jwt.token");
        when(jwtTokenProvider.validateAccessToken("mobile.jwt.token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("mobile.jwt.token")).thenReturn(userId);
        when(jwtTokenProvider.extractTenantId("mobile.jwt.token")).thenReturn(tenantId);
        when(jwtTokenProvider.extractRole("mobile.jwt.token")).thenReturn("member");
        when(jwtTokenProvider.extractEmail("mobile.jwt.token")).thenReturn("mobile@test.com");

        filter.doFilterInternal(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((TenantUserPrincipal) auth.getPrincipal()).userId()).isEqualTo(userId);
        assertThat(request.getAttribute("BEARER_AUTHENTICATED")).isEqualTo(Boolean.TRUE);
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_bearerHeaderTakesPrecedenceOverCookie() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer header.token");
        request.setCookies(new Cookie("access_token", "cookie.token"));
        when(jwtTokenProvider.validateAccessToken("header.token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("header.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractTenantId("header.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractRole("header.token")).thenReturn("member");
        when(jwtTokenProvider.extractEmail("header.token")).thenReturn("u@e.com");

        filter.doFilterInternal(request, response, chain);

        verify(jwtTokenProvider).validateAccessToken("header.token");
        verify(jwtTokenProvider, never()).validateAccessToken("cookie.token");
    }

    @Test
    void doFilterInternal_cookieAuth_doesNotMarkBearerAttribute() throws ServletException, IOException {
        request.setCookies(new Cookie("access_token", "valid.cookie"));
        when(jwtTokenProvider.validateAccessToken("valid.cookie")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("valid.cookie")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractTenantId("valid.cookie")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractRole("valid.cookie")).thenReturn("member");
        when(jwtTokenProvider.extractEmail("valid.cookie")).thenReturn("u@e.com");

        filter.doFilterInternal(request, response, chain);

        assertThat(request.getAttribute("BEARER_AUTHENTICATED")).isNull();
    }

    @Test
    void doFilterInternal_authorizationHeaderWithoutBearerPrefix_fallsBackToCookie()
            throws ServletException, IOException {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        request.setCookies(new Cookie("access_token", "cookie.token"));
        when(jwtTokenProvider.validateAccessToken("cookie.token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("cookie.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractTenantId("cookie.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractRole("cookie.token")).thenReturn("member");
        when(jwtTokenProvider.extractEmail("cookie.token")).thenReturn("u@e.com");

        filter.doFilterInternal(request, response, chain);

        verify(jwtTokenProvider).validateAccessToken("cookie.token");
        assertThat(request.getAttribute("BEARER_AUTHENTICATED")).isNull();
    }

    @Test
    void doFilterInternal_otherCookiesPresent_butNoAccessTokenCookie_skipsAuthentication()
            throws ServletException, IOException {
        request.setCookies(new Cookie("XSRF-TOKEN", "csrf-value"),
                new Cookie("session", "abc"));

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void doFilterInternal_emptyAccessTokenCookie_passesEmptyStringToValidator() throws ServletException, IOException {
        request.setCookies(new Cookie("access_token", ""));
        when(jwtTokenProvider.validateAccessToken("")).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenProvider).validateAccessToken("");
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_clearsMdcAfterRequest() throws ServletException, IOException {
        request.setCookies(new Cookie("access_token", "valid.token"));
        when(jwtTokenProvider.validateAccessToken("valid.token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("valid.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractTenantId("valid.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractRole("valid.token")).thenReturn("member");
        when(jwtTokenProvider.extractEmail("valid.token")).thenReturn("u@e.com");

        filter.doFilterInternal(request, response, chain);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    void doFilterInternal_populatesMdcDuringChainInvocation() throws ServletException, IOException {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        request.setCookies(new Cookie("access_token", "t"));
        when(jwtTokenProvider.validateAccessToken("t")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("t")).thenReturn(userId);
        when(jwtTokenProvider.extractTenantId("t")).thenReturn(tenantId);
        when(jwtTokenProvider.extractRole("t")).thenReturn("member");
        when(jwtTokenProvider.extractEmail("t")).thenReturn("e@e.com");

        var captured = new String[3];
        org.mockito.Mockito.doAnswer(inv -> {
            captured[0] = MDC.get("requestId");
            captured[1] = MDC.get("userId");
            captured[2] = MDC.get("tenantId");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(request, response, chain);

        assertThat(captured[0]).isNotBlank();
        assertThat(captured[1]).isEqualTo(userId.toString());
        assertThat(captured[2]).isEqualTo(tenantId.toString());
    }

    @Test
    void doFilterInternal_unauthenticatedRequest_setsOnlyRequestIdInMdc() throws ServletException, IOException {
        var captured = new String[3];
        org.mockito.Mockito.doAnswer(inv -> {
            captured[0] = MDC.get("requestId");
            captured[1] = MDC.get("userId");
            captured[2] = MDC.get("tenantId");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(request, response, chain);

        assertThat(captured[0]).isNotBlank();
        assertThat(captured[1]).isNull();
        assertThat(captured[2]).isNull();
    }

    @Test
    void doFilterInternal_clearsMdcEvenWhenChainThrows() throws ServletException, IOException {
        doThrow(new ServletException("boom")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    void doFilterInternal_usesXRequestIdHeaderAsRequestId() throws ServletException, IOException {
        request.addHeader("X-Request-ID", "client-abc-123");
        var captured = new String[1];
        org.mockito.Mockito.doAnswer(inv -> {
            captured[0] = MDC.get("requestId");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(request, response, chain);

        assertThat(captured[0]).isEqualTo("client-abc-123");
    }

    @Test
    void doFilterInternal_truncatesLongXRequestIdTo32Chars() throws ServletException, IOException {
        var longId = "a".repeat(64);
        request.addHeader("X-Request-ID", longId);
        var captured = new String[1];
        org.mockito.Mockito.doAnswer(inv -> {
            captured[0] = MDC.get("requestId");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(request, response, chain);

        assertThat(captured[0]).hasSize(32).isEqualTo("a".repeat(32));
    }

    @Test
    void doFilterInternal_cryptographicallyValidButSessionRejected_leavesContextEmpty()
            throws ServletException, IOException {
        request.setCookies(new Cookie("access_token", "revoked.jwt.token"));
        when(jwtTokenProvider.validateAccessToken("revoked.jwt.token")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("revoked.jwt.token")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractGeneration("revoked.jwt.token")).thenReturn(3);
        when(sessionStateValidator.isSessionValid(any(UUID.class), anyInt())).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_sessionValid_passesTokenGenerationToValidator()
            throws ServletException, IOException {
        var userId = UUID.randomUUID();
        request.setCookies(new Cookie("access_token", "t"));
        when(jwtTokenProvider.validateAccessToken("t")).thenReturn(true);
        when(jwtTokenProvider.extractUserId("t")).thenReturn(userId);
        when(jwtTokenProvider.extractTenantId("t")).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.extractRole("t")).thenReturn("member");
        when(jwtTokenProvider.extractEmail("t")).thenReturn("u@e.com");
        when(jwtTokenProvider.extractGeneration("t")).thenReturn(11);

        filter.doFilterInternal(request, response, chain);

        verify(sessionStateValidator).isSessionValid(userId, 11);
    }

    @Test
    void doFilterInternal_generatesRequestIdWhenHeaderAbsent() throws ServletException, IOException {
        var captured = new String[1];
        org.mockito.Mockito.doAnswer(inv -> {
            captured[0] = MDC.get("requestId");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(request, response, chain);

        assertThat(captured[0])
                .hasSize(12)
                .matches("[0-9a-f]{12}");
    }
}
