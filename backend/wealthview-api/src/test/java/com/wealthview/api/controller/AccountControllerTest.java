package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.account.AccountService;
import com.wealthview.core.account.dto.AccountRequest;
import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.common.PageResponse;
import com.wealthview.core.exception.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedViewer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AccountService accountService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private AccountResponse sampleResponse() {
        return new AccountResponse(ACCOUNT_ID, "Brokerage", "brokerage", "Fidelity", OffsetDateTime.now());
    }

    @Test
    void create_validInput_returns201() throws Exception {
        when(accountService.create(eq(TENANT_ID), any(AccountRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/accounts")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Brokerage", "type": "brokerage", "institution": "Fidelity"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Brokerage"))
                .andExpect(jsonPath("$.type").value("brokerage"));
    }

    @Test
    void create_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"institution": "Fidelity"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_authenticated_returns200() throws Exception {
        var page = new PageResponse<>(List.of(sampleResponse()), 0, 25, 1L);
        when(accountService.list(eq(TENANT_ID), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/accounts")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Brokerage"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void get_existingAccount_returns200() throws Exception {
        when(accountService.get(TENANT_ID, ACCOUNT_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/accounts/{id}", ACCOUNT_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Brokerage"));
    }

    @Test
    void get_nonExistent_returns404() throws Exception {
        when(accountService.get(TENANT_ID, ACCOUNT_ID))
                .thenThrow(new EntityNotFoundException("Account not found"));

        mockMvc.perform(get("/api/v1/accounts/{id}", ACCOUNT_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void update_validInput_returns200() throws Exception {
        when(accountService.update(eq(TENANT_ID), eq(ACCOUNT_ID), any(AccountRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(put("/api/v1/accounts/{id}", ACCOUNT_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Brokerage", "type": "brokerage", "institution": "Fidelity"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void delete_existing_returns204() throws Exception {
        doNothing().when(accountService).delete(TENANT_ID, ACCOUNT_ID);

        mockMvc.perform(delete("/api/v1/accounts/{id}", ACCOUNT_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void create_viewerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .with(authenticatedViewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Brokerage", "type": "brokerage"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isForbidden());
    }
}
