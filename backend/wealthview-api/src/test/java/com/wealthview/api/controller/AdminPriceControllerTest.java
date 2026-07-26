package com.wealthview.api.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.price.PriceService;
import com.wealthview.core.price.dto.CsvImportResult;
import com.wealthview.core.price.dto.PriceResponse;
import com.wealthview.core.price.dto.PriceSyncStatus;
import com.wealthview.core.price.dto.YahooSyncResult;
import com.wealthview.core.pricefeed.PriceSyncService;
import com.wealthview.core.pricefeed.dto.FinnhubSyncResult;

import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(AdminPriceController.class)
class AdminPriceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PriceService priceService;

    @MockitoBean
    private PriceSyncService priceSyncService;

    @Test
    void triggerPriceSync_whenFinnhubConfigured_returns200() throws Exception {
        when(priceSyncService.syncDailyPrices())
                .thenReturn(new FinnhubSyncResult(12, 12, List.of()));

        mockMvc.perform(post("/api/v1/admin/prices/sync")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(12))
                .andExpect(jsonPath("$.total").value(12));
    }

    @Test
    void getPriceStatus_superAdmin_returns200() throws Exception {
        var statuses = List.of(
                new PriceSyncStatus("AAPL", LocalDate.of(2024, 3, 20), "finnhub", false),
                new PriceSyncStatus("MSFT", LocalDate.of(2024, 3, 15), "manual", true));
        when(priceService.getSyncStatus()).thenReturn(statuses);

        mockMvc.perform(get("/api/v1/admin/prices/status")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].stale").value(false))
                .andExpect(jsonPath("$[1].symbol").value("MSFT"))
                .andExpect(jsonPath("$[1].stale").value(true));
    }

    @Test
    void getPriceStatus_admin_returns200() throws Exception {
        when(priceService.getSyncStatus()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/prices/status")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void syncFromYahoo_superAdmin_returns200() throws Exception {
        when(priceService.getSyncStatus()).thenReturn(
                List.of(new PriceSyncStatus("AAPL", LocalDate.now(), "finnhub", false)));
        when(priceService.syncFromYahoo(List.of("AAPL")))
                .thenReturn(new YahooSyncResult(2, 0, List.of()));

        mockMvc.perform(post("/api/v1/admin/prices/yahoo/sync")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.failures").isEmpty());
    }

    @Test
    void fetchFromYahoo_superAdmin_returns200() throws Exception {
        when(priceService.fetchFromYahoo(any())).thenReturn(List.of(
                new PriceResponse("AAPL", LocalDate.of(2024, 1, 2),
                        new BigDecimal("185.50"), "yahoo")));

        mockMvc.perform(post("/api/v1/admin/prices/yahoo/fetch")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbols": ["AAPL"],
                                    "from_date": "2024-01-01",
                                    "to_date": "2024-01-05"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].close_price").value(185.50));
    }

    @Test
    void saveYahooPrices_superAdmin_returns204() throws Exception {
        when(priceService.bulkUpsertPrices(any(), eq("yahoo"))).thenReturn(2);

        mockMvc.perform(post("/api/v1/admin/prices/yahoo/save")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prices": [
                                        {"symbol": "AAPL", "date": "2024-01-02", "close_price": 185.50},
                                        {"symbol": "MSFT", "date": "2024-01-02", "close_price": 370.25}
                                    ]
                                }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void importCsv_superAdmin_returns200() throws Exception {
        when(priceService.importCsv(any())).thenReturn(new CsvImportResult(3, List.of()));

        var file = new MockMultipartFile("file", "prices.csv", "text/csv",
                "symbol,date,close_price\nAAPL,2024-01-02,185.50".getBytes());

        mockMvc.perform(multipart("/api/v1/admin/prices/csv")
                        .file(file)
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void importCsv_admin_returns200() throws Exception {
        when(priceService.importCsv(any())).thenReturn(new CsvImportResult(0, List.of()));

        var file = new MockMultipartFile("file", "prices.csv", "text/csv",
                "symbol,date,close_price\nAAPL,2024-01-02,185.50".getBytes());

        mockMvc.perform(multipart("/api/v1/admin/prices/csv")
                        .file(file)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void browseSymbolPrices_superAdmin_returns200() throws Exception {
        when(priceService.browseSymbol(eq("AAPL"), any(), any())).thenReturn(List.of(
                new PriceResponse("AAPL", LocalDate.of(2024, 1, 2), new BigDecimal("185.50"), "manual")));

        mockMvc.perform(get("/api/v1/admin/prices/AAPL/history")
                        .with(authenticatedSuperAdmin())
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].close_price").value(185.50));
    }

    @Test
    void deletePrice_superAdmin_returns204() throws Exception {
        doNothing().when(priceService).deletePrice("AAPL", LocalDate.of(2024, 1, 2));

        mockMvc.perform(delete("/api/v1/admin/prices/AAPL/2024-01-02")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePrice_notFound_returns404() throws Exception {
        doThrow(new EntityNotFoundException("Price not found"))
                .when(priceService).deletePrice(anyString(), any(LocalDate.class));

        mockMvc.perform(delete("/api/v1/admin/prices/AAPL/2024-01-02")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isNotFound());
    }
}
