package com.wealthview.api.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionService;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.auth.dto.SessionResponse;

import static com.wealthview.api.testutil.ControllerTestUtils.USER_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SessionStateValidator sessionStateValidator;

    private static final UUID SESSION_ID = UUID.randomUUID();

    private SessionResponse sampleSession() {
        return new SessionResponse(SESSION_ID, "My iPhone", "bearer", "127.0.0.1",
                "Mozilla/5.0", OffsetDateTime.now(), OffsetDateTime.now(), true);
    }

    @Test
    void list_authenticated_returns200() throws Exception {
        when(sessionService.listForUser(eq(USER_ID), any())).thenReturn(List.of(sampleSession()));

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].device_label").value("My iPhone"))
                .andExpect(jsonPath("$[0].transport").value("bearer"))
                .andExpect(jsonPath("$[0].current").value(true));
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revoke_existingSession_returns204() throws Exception {
        when(sessionService.revoke(eq(USER_ID), eq(SESSION_ID))).thenReturn(true);

        mockMvc.perform(delete("/api/v1/auth/sessions/{id}", SESSION_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void revoke_sessionNotFound_returns404() throws Exception {
        // revoke returns false when session doesn't belong to the user,
        // controller throws EntityNotFoundException → GlobalExceptionHandler
        // returns the standard {error, message, status} envelope.
        when(sessionService.revoke(eq(USER_ID), eq(SESSION_ID))).thenReturn(false);

        mockMvc.perform(delete("/api/v1/auth/sessions/{id}", SESSION_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void revokeAllOther_authenticated_returns204() throws Exception {
        // revokeAllOther returns int, not void — just let it return 0 by default
        when(sessionService.revokeAllOther(eq(USER_ID), any())).thenReturn(0);

        mockMvc.perform(delete("/api/v1/auth/sessions")
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void revokeAllOther_unauthenticated_returns403() throws Exception {
        // DELETE without a CSRF token is denied by the CSRF filter (403) before
        // the auth check, which is the correct layered security behavior.
        mockMvc.perform(delete("/api/v1/auth/sessions"))
                .andExpect(status().isForbidden());
    }
}
