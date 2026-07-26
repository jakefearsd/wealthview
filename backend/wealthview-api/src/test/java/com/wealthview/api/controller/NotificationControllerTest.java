package com.wealthview.api.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.notification.NotificationPreferenceService;
import com.wealthview.core.notification.dto.NotificationPreferenceRequest;
import com.wealthview.core.notification.dto.NotificationPreferenceResponse;
import tools.jackson.databind.ObjectMapper;

import static com.wealthview.api.testutil.ControllerTestUtils.USER_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationPreferenceService preferenceService;

    @Test
    void getPreferences_returnsPreferences() throws Exception {
        when(preferenceService.getPreferences(USER_ID)).thenReturn(List.of(
                new NotificationPreferenceResponse("LARGE_TRANSACTION", true),
                new NotificationPreferenceResponse("IMPORT_COMPLETE", true),
                new NotificationPreferenceResponse("IMPORT_FAILED", false)
        ));

        mockMvc.perform(get("/api/v1/notifications/preferences").with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].notification_type").value("LARGE_TRANSACTION"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[2].enabled").value(false));
    }

    @Test
    void updatePreferences_returns200() throws Exception {
        var request = new NotificationPreferenceRequest(List.of(
                new NotificationPreferenceRequest.PreferenceItem("LARGE_TRANSACTION", false)
        ));

        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(preferenceService).updatePreferences(eq(USER_ID), any(NotificationPreferenceRequest.class));
    }

    @Test
    void getPreferences_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/preferences"))
                .andExpect(status().isUnauthorized());
    }
}
