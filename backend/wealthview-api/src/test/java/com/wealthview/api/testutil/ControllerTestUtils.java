package com.wealthview.api.testutil;

import com.wealthview.api.security.TenantUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

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
            return SecurityMockMvcRequestPostProcessors.securityContext(context).postProcessRequest(request);
        };
    }
}
