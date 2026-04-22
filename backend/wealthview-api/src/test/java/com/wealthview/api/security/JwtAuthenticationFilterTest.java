package com.wealthview.api.security;

import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
        // Default: session validator approves. Tests that care about rejection
        // override this with a specific expectation.
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
    void doFilterInternal_validBearerToken_populatesSecurityContext() throws ServletException, IOException {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        request.addHeader("Authorization", "Bearer valid.jwt.token");
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
        request.addHeader("Authorization", "Bearer admin.token");
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
        request.addHeader("Authorization", "Bearer bad.token");
        when(jwtTokenProvider.validateAccessToken("bad.token")).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verify(jwtTokenProvider, never()).extractUserId(anyString());
    }

    @Test
    void doFilterInternal_noAuthorizationHeader_skipsAuthentication() throws ServletException, IOException {
        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void doFilterInternal_nonBearerAuthorizationHeader_skipsAuthentication() throws ServletException, IOException {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void doFilterInternal_bearerWithNoSpace_treatedAsNonBearer() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearerfoo");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void doFilterInternal_bearerWithEmptyToken_passesEmptyStringToValidator() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer ");
        when(jwtTokenProvider.validateAccessToken("")).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenProvider).validateAccessToken("");
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_clearsMdcAfterRequest() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer valid.token");
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
        request.addHeader("Authorization", "Bearer t");
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
        // Even when JWT signature, issuer, audience, and expiry are all valid,
        // the filter must consult SessionStateValidator — this is what lets
        // logout, password reset, and user/tenant disablement revoke tokens
        // before their 15-minute expiry.
        request.addHeader("Authorization", "Bearer revoked.jwt.token");
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
        // The filter must forward the token's generation claim to the validator
        // so staleness is detectable. If it defaults to 0 or discards the claim,
        // every refresh breaks the revocation check.
        var userId = UUID.randomUUID();
        request.addHeader("Authorization", "Bearer t");
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
