package com.wealthview.api.security;

import com.wealthview.api.controller.AuthController;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.AuthService;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private SessionStateValidator sessionStateValidator;

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
