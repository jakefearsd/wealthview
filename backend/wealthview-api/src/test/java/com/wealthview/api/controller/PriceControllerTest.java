package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.price.PriceService;
import com.wealthview.core.price.dto.PriceRequest;
import com.wealthview.core.price.dto.PriceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PriceController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class PriceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PriceService priceService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void create_validInput_returns201() throws Exception {
        var response = new PriceResponse("AAPL", LocalDate.of(2025, 1, 15),
                new BigDecimal("185.50"), "manual");
        when(priceService.createPrice(any(PriceRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/prices")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol": "AAPL", "date": "2025-01-15", "close_price": 185.50}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("AAPL"));
    }

    @Test
    void getLatest_existingSymbol_returns200() throws Exception {
        var response = new PriceResponse("AAPL", LocalDate.of(2025, 1, 15),
                new BigDecimal("185.50"), "manual");
        when(priceService.getLatestPrice("AAPL")).thenReturn(response);

        mockMvc.perform(get("/api/v1/prices/AAPL/latest")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.close_price").value(185.50));
    }

    @Test
    void getLatest_unknownSymbol_returns404() throws Exception {
        when(priceService.getLatestPrice("UNKNOWN"))
                .thenThrow(new EntityNotFoundException("No price found for symbol: UNKNOWN"));

        mockMvc.perform(get("/api/v1/prices/UNKNOWN/latest")
                        .with(authenticatedAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_missingSymbol_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/prices")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2025-01-15", "close_price": 185.50}
                                """))
                .andExpect(status().isBadRequest());
    }
}
