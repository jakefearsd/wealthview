package com.wealthview.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.controller.AuthController;
import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.auth.AuthService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WealthViewControllerTest(AuthController.class)
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void publicResponse_includesPermissionsPolicyHeaderDisablingSensitiveFeatures() throws Exception {
        // Any response through the security filter chain should carry Permissions-Policy
        // so the browser refuses to grant geolocation/camera/mic/payment even if an XSS
        // lands. /api/v1/auth/me is reachable without auth (returns 401) but still goes
        // through the header-writing filter chain.
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(header().string("Permissions-Policy",
                        "geolocation=(), microphone=(), camera=(), payment=()"));
    }
}
