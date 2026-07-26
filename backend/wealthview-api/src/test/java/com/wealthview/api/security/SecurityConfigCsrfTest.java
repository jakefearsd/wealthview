package com.wealthview.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.controller.AuthController;
import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.auth.AuthService;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(AuthController.class)
class SecurityConfigCsrfTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;


    @Test
    void post_login_doesNotRequireCsrf() throws Exception {
        // Login is the entry point — no prior session, no XSRF-TOKEN cookie possible —
        // so it must be exempt from CSRF.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\",\"password\":\"x\"}"))
                .andExpect(status().is(org.hamcrest.Matchers.not(403)));
    }

    @Test
    void post_register_doesNotRequireCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\",\"password\":\"x\",\"invite_code\":\"ABC\"}"))
                .andExpect(status().is(org.hamcrest.Matchers.not(403)));
    }

    @Test
    void post_logout_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(authenticatedAdminWithoutCsrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void post_logout_withCsrf_succeeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(authenticatedAdmin())
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor
            authenticatedAdminWithoutCsrf() {
        // Same as authenticatedAdmin() but without the auto-attached CSRF token,
        // so we can prove CSRF protection actually rejects the request.
        return request -> {
            var principal = com.wealthview.api.testutil.ControllerTestUtils.adminPrincipal();
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);
            return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .securityContext(context).postProcessRequest(request);
        };
    }
}
