package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.SpendingProfileService;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SpendingProfileController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class SpendingProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SpendingProfileService spendingProfileService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID PROFILE_ID = UUID.randomUUID();

    private SpendingProfileResponse sampleProfile() {
        return new SpendingProfileResponse(
                PROFILE_ID, "Retirement Spending",
                new BigDecimal("40000"), new BigDecimal("20000"),
                List.of(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void create_validInput_returns201() throws Exception {
        when(spendingProfileService.createProfile(eq(TENANT_ID), any()))
                .thenReturn(sampleProfile());

        mockMvc.perform(post("/api/v1/spending-profiles")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Retirement Spending",
                                    "essential_expenses": 40000,
                                    "discretionary_expenses": 20000,
                                    "income_streams": []
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Retirement Spending"))
                .andExpect(jsonPath("$.essential_expenses").value(40000));
    }

    @Test
    void list_authenticated_returns200() throws Exception {
        when(spendingProfileService.listProfiles(TENANT_ID))
                .thenReturn(List.of(sampleProfile()));

        mockMvc.perform(get("/api/v1/spending-profiles")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Retirement Spending"));
    }

    @Test
    void get_existingProfile_returns200() throws Exception {
        when(spendingProfileService.getProfile(TENANT_ID, PROFILE_ID))
                .thenReturn(sampleProfile());

        mockMvc.perform(get("/api/v1/spending-profiles/{id}", PROFILE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retirement Spending"));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        when(spendingProfileService.getProfile(TENANT_ID, PROFILE_ID))
                .thenThrow(new EntityNotFoundException("Spending profile not found"));

        mockMvc.perform(get("/api/v1/spending-profiles/{id}", PROFILE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_validInput_returns200() throws Exception {
        when(spendingProfileService.updateProfile(eq(TENANT_ID), eq(PROFILE_ID), any()))
                .thenReturn(sampleProfile());

        mockMvc.perform(put("/api/v1/spending-profiles/{id}", PROFILE_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Retirement Spending",
                                    "essential_expenses": 40000,
                                    "discretionary_expenses": 20000,
                                    "income_streams": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retirement Spending"));
    }

    @Test
    void delete_existingProfile_returns204() throws Exception {
        doNothing().when(spendingProfileService).deleteProfile(TENANT_ID, PROFILE_ID);

        mockMvc.perform(delete("/api/v1/spending-profiles/{id}", PROFILE_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }
}
