package com.wealthview.api.testutil;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.wealthview.api.security.TenantUserPrincipal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class ControllerTestUtils {

    public static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final String EMAIL = "test@example.com";

    private ControllerTestUtils() {
    }

    public static TenantUserPrincipal adminPrincipal() {
        return new TenantUserPrincipal(USER_ID, TENANT_ID, EMAIL, "admin");
    }

    public static TenantUserPrincipal memberPrincipal() {
        return new TenantUserPrincipal(USER_ID, TENANT_ID, EMAIL, "member");
    }

    public static TenantUserPrincipal viewerPrincipal() {
        return new TenantUserPrincipal(USER_ID, TENANT_ID, EMAIL, "viewer");
    }

    public static TenantUserPrincipal superAdminPrincipal() {
        return new TenantUserPrincipal(USER_ID, TENANT_ID, EMAIL, "super_admin");
    }

    public static RequestPostProcessor authenticatedAdmin() {
        return authenticatedAs(adminPrincipal());
    }

    public static RequestPostProcessor authenticatedMember() {
        return authenticatedAs(memberPrincipal());
    }

    public static RequestPostProcessor authenticatedViewer() {
        return authenticatedAs(viewerPrincipal());
    }

    public static RequestPostProcessor authenticatedSuperAdmin() {
        return authenticatedAs(superAdminPrincipal());
    }

    public static RequestPostProcessor authenticatedAs(TenantUserPrincipal principal) {
        return request -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            // Authenticated requests in our cookie/CSRF model always carry a valid
            // CSRF token, so test fixtures attach one alongside the security context.
            // GET requests are unaffected (CSRF check only fires on mutations).
            request = (org.springframework.mock.web.MockHttpServletRequest)
                    SecurityMockMvcRequestPostProcessors.securityContext(context).postProcessRequest(request);
            return SecurityMockMvcRequestPostProcessors.csrf().postProcessRequest(request);
        };
    }

    /**
     * Asserts the standard error envelope (see CLAUDE.md's {@code { "error", "message", "status" }}
     * shape) for a given {@link HttpStatus}: the HTTP response status itself, {@code $.error}
     * (always the {@code HttpStatus} enum name -- see {@code GlobalExceptionHandler}), and
     * {@code $.status}. Callers that also want to pin {@code $.message} should chain an additional
     * {@code jsonPath("$.message")} expectation.
     */
    public static ResultMatcher errorEnvelope(HttpStatus status) {
        return result -> {
            status().is(status.value()).match(result);
            jsonPath("$.error").value(status.name()).match(result);
            jsonPath("$.status").value(status.value()).match(result);
        };
    }
}
